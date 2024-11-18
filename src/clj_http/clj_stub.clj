(ns clj-http.clj_stub
  (:import [java.util.regex Pattern]
           [java.util Map]
           [java.net URLEncoder]
           [org.apache.http HttpEntity])
  (:require [clj-http.core]
            [stub.shared :as shared])
  (:use [robert.hooke]
        [clojure.math.combinatorics]
        [clojure.string :only [join split]]))

(defmacro with-http-stub-in-isolation
  "Makes all wrapped clj-http requests first match against given routes.
  If no route matches, an exception is thrown."
  [routes & body]
  `(binding [shared/*in-isolation* true]
    (with-http-stub ~routes ~@body)))

(defmacro with-http-stub
  "Makes all wrapped clj-http requests first match against given routes.
  The actual HTTP request will be sent only if no matches are found."
  [routes & body]
  `(let [s# ~routes]
    (assert (map? s#))
    (binding [shared/*stub-routes* s#
              shared/*call-counts* (atom {})
              shared/*expected-counts* (atom {})]
      (try
        (let [result# (do ~@body)]
          (shared/validate-all-call-counts)
          result#)
        (finally
          (reset! shared/*call-counts* {})
          (reset! shared/*expected-counts* {}))))))

(defmacro with-global-http-stub
  [routes & body]
  `(let [s# ~routes]
     (assert (map? s#))
     (with-redefs [shared/*stub-routes* s#
                   shared/*call-counts* (atom {})
                   shared/*expected-counts* (atom {})]
       (try
         (let [result# (do ~@body)]
           (shared/validate-all-call-counts)
           result#)
         (finally
           (reset! shared/*call-counts* {})
           (reset! shared/*expected-counts* {}))))))

(defmacro with-global-http-stub-in-isolation
  [routes & body]
  `(with-redefs [shared/*in-isolation* true]
     (with-global-http-stub ~routes ~@body)))

(defn- potential-uris-for [request-map]
  (shared/defaults-or-value #{"/" "" nil} (:uri request-map)))

(defprotocol RouteMatcher
  (matches [address method request]))

(defn url-encode
  "encodes string into valid URL string"
  [string]
  (some-> string str (URLEncoder/encode "UTF-8") (.replace "+" "%20")))

(defn utf8-bytes
    "Returns the UTF-8 bytes corresponding to the given string."
    [^String s]
    (.getBytes s "UTF-8"))

(defn- byte-array?
  "Is `obj` a java byte array?"
  [obj]
  (instance? (Class/forName "[B") obj))

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

(extend-protocol RouteMatcher
  String
  (matches [address method request]
    (matches (re-pattern (Pattern/quote address)) method request))

  Pattern
  (matches [address method request]
    (let [address-strings (map shared/address-string-for (shared/potential-alternatives-to request potential-uris-for))]
      (and (shared/methods-match? method request)
           (some #(re-matches address %) address-strings))))

  Map
  (matches [address method request]
    (let [{expected-query-params :query-params} address]
      (and (or (nil? expected-query-params)
               (shared/query-params-match? expected-query-params request))
           (let [request (cond-> request expected-query-params (dissoc :query-string))]
             (matches (:address address) method request))))))

(defn- process-handler [method address handler]
  (let [route-key (str address method)]
    (cond
      ;; Handler is a function with times metadata
      (and (fn? handler) (:times (meta handler)))
      (do
        (swap! shared/*expected-counts* assoc route-key (:times (meta handler)))
        [method address {:handler handler}])

      ;; Handler is a map with :handler and :times
      (and (map? handler) (:handler handler))
      (do
        (when-let [times (:times handler)]
          (swap! shared/*expected-counts* assoc route-key times))
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

(defn- get-matching-route
  [request]
  (->> shared/*stub-routes*
       flatten-routes
       (filter #(matches (:address %) (:method %) request))
       first))

(defn- handle-request-for-route
  [request route]
  (let [route-handler (:handler route)
        handler-fn (if (map? route-handler) (:handler route-handler) route-handler)
        route-key (str (:address route) (:method route))
        _ (swap! shared/*call-counts* update route-key (fnil inc 0))
        response (shared/create-response handler-fn (unwrap-body request))]
    (assoc response :body (body-bytes (:body response)))))

(defn- throw-no-stub-route-exception
  [request]
  (throw (Exception.
           ^String
           (apply format
                  "No matching stub route found to handle request. Request details: \n\t%s \n\t%s \n\t%s \n\t%s \n\t%s "
                  (select-keys request [:scheme :request-method :server-name :uri :query-string])))))

(defn try-intercept
  ([origfn request respond raise]
   (if-let [matching-route (get-matching-route request)]
     (future
       (try (respond (handle-request-for-route request matching-route))
            (catch Exception e (raise e)))
       nil)
     (if shared/*in-isolation*
       (try (throw-no-stub-route-exception request)
            (catch Exception e
              (raise e)
              (throw e)))
       (origfn request respond raise))))
  ([origfn request]
   (if-let [matching-route (get-matching-route request)]
     (handle-request-for-route request matching-route)
     (if shared/*in-isolation*
       (throw-no-stub-route-exception request)
       (origfn request)))))

(defn initialize-request-hook []
  (add-hook
   #'clj-http.core/request
   #'try-intercept))

(initialize-request-hook)
