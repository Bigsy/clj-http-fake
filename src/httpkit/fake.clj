(ns httpkit.fake
  (:import [java.util.regex Pattern]
           [java.net URLEncoder URLDecoder])
  (:require [org.httpkit.client :as http]
            [ring.util.codec :as ring-codec]
            [robert.hooke :refer [add-hook]]
            [clojure.math.combinatorics :refer :all]
            [clojure.string :as str]))

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

(defprotocol RouteMatcher
  (matches [address method request]))

(defn- defaults-or-value [defaults value]
  (if (contains? defaults value) (reverse (vec defaults)) (vector value)))

(defn- normalize-path [path]
  (cond
    (nil? path) "/"
    (str/blank? path) "/"
    (str/ends-with? path "/") path
    :else (str path "/")))

(defn- parse-url [url]
  (let [[url query] (str/split url #"\?" 2)
        [scheme rest] (if (str/includes? url "://")
                       (str/split url #"://" 2)
                       [nil url])
        [server-name path] (if (str/includes? rest "/")
                           (let [idx (str/index-of rest "/")]
                             [(subs rest 0 idx) (subs rest idx)])
                           [rest "/"])
        [server-name port] (if (str/includes? server-name ":")
                           (str/split server-name #":" 2)
                           [server-name nil])]
    {:scheme scheme
     :server-name server-name
     :server-port (when port (Integer/parseInt port))
     :uri (normalize-path path)
     :query-string query}))

(defn- potential-server-ports-for [request-map]
  (defaults-or-value #{80 nil} (:server-port request-map)))

(defn- potential-uris-for [request-map]
  (let [uri (:uri request-map)]
    (if (str/blank? uri)
      ["/" "" nil]
      [(normalize-path uri) (str/replace uri #"/+$" "")])))

(defn- potential-schemes-for [request-map]
  (defaults-or-value #{"http" nil} (:scheme request-map)))

(defn- potential-query-strings-for [request-map]
  (let [queries (defaults-or-value #{"" nil} (:query-string request-map))
        query-supplied (= (count queries) 1)]
    (if query-supplied
      (map (partial str/join "&") (permutations (str/split (first queries) #"&|;")))
      queries)))

(defn- potential-alternatives-to [request]
  (let [schemes (potential-schemes-for request)
        server-ports (potential-server-ports-for request)
        uris (potential-uris-for request)
        query-strings (potential-query-strings-for request)
        combinations (cartesian-product query-strings schemes server-ports uris)]
    (map #(merge request (zipmap [:query-string :scheme :server-port :uri] %)) combinations)))

(defn- address-string-for [request-map]
  (let [{:keys [scheme server-name server-port uri query-string]} request-map]
    (str/join [(if (nil? scheme) "" (str scheme "://"))
               server-name
               (if (nil? server-port) "" (str ":" server-port))
               (if (nil? uri) "" uri)
               (if (nil? query-string) "" (str "?" query-string))])))

(defn- query-params-match? [expected-query-params request]
  (let [actual-query-params (or (:query-params request) {})]
    (and (= (count expected-query-params) (count actual-query-params))
         (every? (fn [[k v]]
                  (= (str v) (str (get actual-query-params k))))
                expected-query-params))))

(defn- matches-url [url request]
  (let [parsed-url (if (string? url) (parse-url url) url)
        req-map (if (:query-params request)
                 (assoc (parse-url (:url request))
                        :query-string (ring-codec/form-encode (:query-params request)))
                 (parse-url (:url request)))
        address-strings (map address-string-for (potential-alternatives-to req-map))]
    (cond
      (instance? Pattern url) (some #(re-matches url %) address-strings)
      :else (some #(= (address-string-for parsed-url) %) address-strings))))

(extend-protocol RouteMatcher
  String
  (matches [address _ request]
    (matches-url address request))

  Pattern
  (matches [address _ request]
    (matches-url address request))

  clojure.lang.PersistentArrayMap
  (matches [address _ request]
    (let [expected-query-params (:query-params address)]
      (and (matches-url (:url address) (dissoc request :query-params))
           (or (nil? expected-query-params)
               (query-params-match? expected-query-params request)))))
            
  clojure.lang.PersistentVector
  (matches [address _ request]
    (let [[req-method url] address]
      (and (or (= req-method :any)
               (= (:method request) req-method))
           (matches-url url request)))))

(defn normalize-request-map [request]
  (let [req (if (string? request) {:url request} request)]
    (merge {:method :get} req)))

(defn- find-matching-route [routes request]
  (first
    (for [[route-key response] routes
          :when (cond
                 (vector? route-key) (let [[method url] route-key]
                                     (and (or (= method :any)
                                            (= method (:method request)))
                                          (matches-url url request)))
                 (map? route-key) (matches route-key nil request)
                 :else (matches-url route-key request))]
      [route-key response])))

(defn- create-response [response request]
  (let [resp (if (fn? response)
               (response request)
               response)]
    (merge {:status 200
            :headers {}
            :body ""}
           resp)))

(defn wrap-request-with-fake [client]
  (fn [req callback]
    (let [request (normalize-request-map req)
          matching-route (find-matching-route *fake-routes* request)
          route-key (first matching-route)]
      (when route-key
        (swap! *call-counts* update-in [(if (vector? route-key)
                                        route-key
                                        [(:url request) (:method request)])] 
                                      (fnil inc 0)))
      (let [response-promise (promise)]
        (if matching-route
          (let [[_ response] matching-route
                resp (create-response response request)]
            (deliver response-promise resp))
          (if *in-isolation*
            (throw (Exception. (str "No matching fake route found for " (:method request) " "
                                  (:url request))))
            (client req #(deliver response-promise %))))
        (callback @response-promise)
        response-promise))))

(defmacro with-fake-routes
  "Makes all wrapped http-kit requests first match against given routes.
  The actual HTTP request will be sent only if no matches are found."
  [routes & body]
  `(let [s# ~routes]
     (assert (map? s#))
     (binding [*fake-routes* s#
               *call-counts* (atom {})
               *expected-counts* (atom {})]
       (with-redefs [http/request (wrap-request-with-fake http/request)]
         (try
           (let [result# (do ~@body)]
             (validate-all-call-counts)
             result#)
           (finally
             (reset! *call-counts* {})
             (reset! *expected-counts* {})))))))

(defmacro with-fake-routes-in-isolation
  "Makes all wrapped http-kit requests first match against given routes.
  If no route matches, an exception is thrown."
  [routes & body]
  `(binding [*in-isolation* true]
     (with-fake-routes ~routes ~@body)))
