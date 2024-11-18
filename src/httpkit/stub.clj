(ns httpkit.stub
  (:import [java.util.regex Pattern]
           [java.net URLEncoder URLDecoder])
  (:require [org.httpkit.client :as http]
            [ring.util.codec :as ring-codec]
            [robert.hooke :refer [add-hook]]
            [clojure.math.combinatorics :refer :all]
            [stub.shared :refer [*fake-routes* *in-isolation* *call-counts* *expected-counts*
                               validate-all-call-counts normalize-path defaults-or-value
                               query-params-match? parse-url potential-server-ports-for
                               potential-schemes-for potential-query-strings-for
                               potential-alternatives-to address-string-for]]
            [clojure.string :as str]))

(defprotocol RouteMatcher
  (matches [address method request]))

(defn- potential-uris-for [request-map]
  (let [uri (:uri request-map)]
    (if (str/blank? uri)
      ["/" "" nil]
      [(normalize-path uri) (str/replace uri #"/+$" "")])))

(defn- matches-url [url request]
  (let [parsed-url (if (string? url) (parse-url url) url)
        req-map (if (:query-params request)
                 (assoc (parse-url (:url request))
                        :query-string (ring-codec/form-encode (:query-params request)))
                 (parse-url (:url request)))
        address-strings (map address-string-for (potential-alternatives-to req-map potential-uris-for))]
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

(defn wrap-request-with-stub [client]
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
            (throw (Exception. (str "No matching stub route found for " (:method request) " "
                                  (:url request))))
            (client req #(deliver response-promise %))))
        (callback @response-promise)
        response-promise))))

(defmacro with-http-stub
  "Makes all wrapped http-kit requests first match against given routes.
  The actual HTTP request will be sent only if no matches are found."
  [routes & body]
  `(let [s# ~routes]
     (assert (map? s#))
     (binding [*fake-routes* s#
               *call-counts* (atom {})
               *expected-counts* (atom {})]
       (with-redefs [http/request (wrap-request-with-stub http/request)]
         (try
           (let [result# (do ~@body)]
             (validate-all-call-counts)
             result#)
           (finally
             (reset! *call-counts* {})
             (reset! *expected-counts* {})))))))

(defmacro with-http-stub-in-isolation
  "Makes all wrapped http-kit requests first match against given routes.
  If no route matches, an exception is thrown."
  [routes & body]
  `(binding [*in-isolation* true]
     (with-http-stub ~routes ~@body)))
