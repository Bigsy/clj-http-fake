(ns clj-http.fake
  (:import [java.util.regex Pattern]
           [java.util Map]
           [java.net URLEncoder URLDecoder]
           [org.apache.http HttpEntity])
  (:require [clj-http.core]
            [ring.util.codec :as ring-codec])
  (:use [robert.hooke]
        [clojure.math.combinatorics]
        [clojure.string :only [join split]]))

(def ^:dynamic *fake-routes* {})
(def ^:dynamic *in-isolation* false)
(def ^:dynamic *call-counts* (atom {}))
(def ^:dynamic *expected-counts* (atom {}))

(defn validate-all-call-counts []
  (doseq [[route-key expected-count] @*expected-counts*]
    (let [actual-count (get @*call-counts* route-key 0)]
      (when (not= actual-count expected-count)
        (throw (Exception. (format "Expected route '%s' to be called %d times but was called %d times"
                                 route-key expected-count actual-count)))))))

(defmacro with-fake-routes-in-isolation
  "Makes all wrapped clj-http requests first match against given routes.
  If no route matches, an exception is thrown."
  [routes & body]
  `(binding [*in-isolation* true]
    (with-fake-routes ~routes ~@body)))

(defmacro with-fake-routes
  "Makes all wrapped clj-http requests first match against given routes.
  The actual HTTP request will be sent only if no matches are found."
  [routes & body]
  `(let [s# ~routes]
    (assert (map? s#))
    (binding [*fake-routes* s#
              *call-counts* (atom {})
              *expected-counts* (atom {})]
      (try
        (let [result# (do ~@body)]
          (validate-all-call-counts)
          result#)
        (finally
          (reset! *call-counts* {})
          (reset! *expected-counts* {}))))))

(defmacro with-global-fake-routes-in-isolation
  [routes & body]
  `(with-redefs [*in-isolation* true]
     (with-global-fake-routes ~routes ~@body)))

(defmacro with-global-fake-routes
  [routes & body]
  `(let [s# ~routes]
     (assert (map? s#))
     (with-redefs [*fake-routes* s#
                   *call-counts* (atom {})
                   *expected-counts* (atom {})]
       (try
         (let [result# (do ~@body)]
           (validate-all-call-counts)
           result#)
         (finally
           (reset! *call-counts* {})
           (reset! *expected-counts* {}))))))

(defn- defaults-or-value [defaults value]
  (if (contains? defaults value) (reverse (vec defaults)) (vector value)))

(defn- potential-server-ports-for [request-map]
  (defaults-or-value #{80 nil} (:server-port request-map)))

(defn- potential-uris-for [request-map]
  (defaults-or-value #{"/" "" nil} (:uri request-map)))

(defn- potential-schemes-for [request-map]
  (defaults-or-value #{:http nil} (keyword (:scheme request-map))))

(defn- potential-query-strings-for [request-map]
  (let [queries (defaults-or-value #{"" nil} (:query-string request-map))
        query-supplied (= (count queries) 1)]
    (if query-supplied
      (map (partial join "&") (permutations (split (first queries) #"&|;")))
      queries)))

(defn- potential-alternatives-to [request]
  (let [schemes       (potential-schemes-for       request)
        server-ports  (potential-server-ports-for  request)
        uris          (potential-uris-for          request)
        query-strings (potential-query-strings-for request)
        combinations  (cartesian-product query-strings schemes server-ports uris)]
    (map #(merge request (zipmap [:query-string :scheme :server-port :uri] %)) combinations)))

(defn- address-string-for [request-map]
  (let [{:keys [scheme server-name server-port uri query-string]} request-map]
    (join [(if (nil? scheme)       "" (format "%s://" (name scheme)))
           server-name
           (if (nil? server-port)  "" (format ":%s"   server-port))
           (if (nil? uri)          "" uri)
           (if (nil? query-string) "" (format "?%s"   query-string))])))

(defprotocol RouteMatcher
  (matches [address method request]))

(defn url-encode
  "encodes string into valid URL string"
  [string]
  (some-> string str (URLEncoder/encode "UTF-8") (.replace "+" "%20")))

(defn- query-params-match?
  [expected-query-params request]
  (let [actual-query-params (or (some-> request :query-string ring-codec/form-decode) {})]
    (and (= (count expected-query-params) (count actual-query-params))
         (every? (fn [[k v]]
                   (= (str v) (get actual-query-params (name k))))
                 expected-query-params))))

(extend-protocol RouteMatcher
  String
  (matches [address method request]
    (matches (re-pattern (Pattern/quote address)) method request))

  Pattern
  (matches [address method request]
    (let [request-method (:request-method request)
          address-strings (map address-string-for (potential-alternatives-to request))]
      (and (contains? (set (distinct [:any request-method])) method)
           (some #(re-matches address %) address-strings))))
  Map
  (matches [address method request]
    (let [{expected-query-params :query-params} address]
      (and (or (nil? expected-query-params)
               (query-params-match? expected-query-params request))
           (let [request (cond-> request expected-query-params (dissoc :query-string))]
             (matches (:address address) method request))))))

(defn- process-handler [method address handler]
  (let [route-key (str address method)]
    (cond
      ;; Handler is a function with times metadata
      (and (fn? handler) (:times (meta handler)))
      (do
        (swap! *expected-counts* assoc route-key (:times (meta handler)))
        [method address {:handler handler}])

      ;; Handler is a map with :handler and :times
      (and (map? handler) (:handler handler))
      (do
        (when-let [times (:times handler)]
          (swap! *expected-counts* assoc route-key times))
        [method address {:handler (:handler handler)}])

      ;; Handler is a direct function
      :else
      [method address {:handler handler}])))

(defn- flatten-routes [routes]
  (let [normalised-routes
        (reduce
         (fn [accumulator [address handlers]]
           (if (map? handlers)
             (into accumulator 
                   (map (fn [[method handler]]
                         (if (= method :times)
                           nil
                           (let [times (get-in handlers [:times method] (:times handlers))]
                             (process-handler method address 
                                            (if times
                                              (with-meta handler {:times times})
                                              handler)))))
                       (dissoc handlers :times)))
             (into accumulator [[:any address {:handler handlers}]])))
         []
         routes)]
    (remove nil? (map #(zipmap [:method :address :handler] %) normalised-routes))))

(defn utf8-bytes
    "Returns the UTF-8 bytes corresponding to the given string."
    [^String s]
    (.getBytes s "UTF-8"))

(let [byte-array-type (Class/forName "[B")]
  (defn- byte-array?
    "Is `obj` a java byte array?"
    [obj]
    (instance? byte-array-type obj)))

(defn body-bytes
  "If `obj` is a byte-array, return it, otherwise use `utf8-bytes`."
  [obj]
  (if (byte-array? obj)
    obj
    (utf8-bytes obj)))

(defn- unwrap-body [request]
  (if (instance? HttpEntity (:body request))
    (assoc request :body (.getContent ^HttpEntity (:body request)))
    request))

(defn- get-matching-route
  [request]
  (->> *fake-routes*
       flatten-routes
       (filter #(matches (:address %) (:method %) request))
       first))

(defn- handle-request-for-route
  [request route]
  (let [route-handler (:handler route)
        handler-fn (if (map? route-handler) (:handler route-handler) route-handler)
        route-key (str (:address route) (:method route))
        _ (swap! *call-counts* update route-key (fnil inc 0))
        response (merge {:status 200 :body ""}
                       (handler-fn (unwrap-body request)))]
    (assoc response :body (body-bytes (:body response)))))

(defn- throw-no-fake-route-exception
  [request]
  (throw (Exception.
           ^String
           (apply format
                  "No matching fake route found to handle request. Request details: \n\t%s \n\t%s \n\t%s \n\t%s \n\t%s "
                  (select-keys request [:scheme :request-method :server-name :uri :query-string])))))

(defn try-intercept
  ([origfn request respond raise]
   (if-let [matching-route (get-matching-route request)]
     (future
       (try (respond (handle-request-for-route request matching-route))
            (catch Exception e (raise e)))
       nil)
     (if *in-isolation*
       (try (throw-no-fake-route-exception request)
            (catch Exception e
              (raise e)
              (throw e)))
       (origfn request respond raise))))
  ([origfn request]
   (if-let [matching-route (get-matching-route request)]
     (handle-request-for-route request matching-route)
     (if *in-isolation*
       (throw-no-fake-route-exception request)
       (origfn request)))))

(defn initialize-request-hook []
  (add-hook
   #'clj-http.core/request
   #'try-intercept))

(initialize-request-hook)
