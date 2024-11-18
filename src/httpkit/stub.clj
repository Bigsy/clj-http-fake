(ns httpkit.stub
  (:import (clojure.lang PersistentArrayMap PersistentVector)
           [java.util.regex Pattern])
  (:require [org.httpkit.client :as http]
            [ring.util.codec :as ring-codec]
            [clojure.math.combinatorics :refer :all]
            [stub.shared :as shared]
            [clojure.string :as str]))

(defprotocol RouteMatcher
  (matches [address method request]))

(defn- potential-uris-for [request-map]
  (let [uri (:uri request-map)]
    (if (str/blank? uri)
      ["/" "" nil]
      [(shared/normalize-path uri) (str/replace uri #"/+$" "")])))

(defn- matches-url [url request]
  (let [parsed-url (if (string? url) (shared/parse-url url) url)
        req-map (if (:query-params request)
                 (assoc (shared/parse-url (:url request))
                        :query-string (ring-codec/form-encode (:query-params request)))
                 (shared/parse-url (:url request)))
        address-strings (map shared/address-string-for (shared/potential-alternatives-to req-map potential-uris-for))]
    (cond
      (instance? Pattern url) (some #(re-matches url %) address-strings)
      :else (some #(= (shared/address-string-for parsed-url) %) address-strings))))

(extend-protocol RouteMatcher
  String
  (matches [address _ request]
    (matches-url address request))

  Pattern
  (matches [address _ request]
    (matches-url address request))

  PersistentArrayMap
  (matches [address _ request]
    (let [expected-query-params (:query-params address)]
      (and (matches-url (:url address) (dissoc request :query-params))
           (or (nil? expected-query-params)
               (shared/query-params-match? expected-query-params request)))))
            
  PersistentVector
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
          matching-route (find-matching-route shared/*stub-routes* request)
          route-key (first matching-route)]
      (when route-key
        (swap! shared/*call-counts* update-in [(if (vector? route-key)
                                        route-key
                                        [(:url request) (:method request)])] 
                                      (fnil inc 0)))
      (let [response-promise (promise)]
        (if matching-route
          (let [[_ response] matching-route
                resp (create-response response request)]
            (deliver response-promise resp))
          (if shared/*in-isolation*
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
     (binding [shared/*stub-routes* s#
               shared/*call-counts* (atom {})
               shared/*expected-counts* (atom {})]
       (with-redefs [http/request (wrap-request-with-stub http/request)]
         (try
           (let [result# (do ~@body)]
             (shared/validate-all-call-counts)
             result#)
           (finally
             (reset! shared/*call-counts* {})
             (reset! shared/*expected-counts* {})))))))

(defmacro with-http-stub-in-isolation
  "Makes all wrapped http-kit requests first match against given routes.
  If no route matches, an exception is thrown."
  [routes & body]
  `(binding [shared/*in-isolation* true]
     (with-http-stub ~routes ~@body)))

(defmacro with-global-http-stub
  "Makes all wrapped http-kit requests first match against given routes.
  The actual HTTP request will be sent only if no matches are found."
  [routes & body]
  `(let [s# ~routes]
     (assert (map? s#))
     (with-redefs [shared/*stub-routes* s#
                   shared/*call-counts* (atom {})
                   shared/*expected-counts* (atom {})
                   http/request (wrap-request-with-stub http/request)]
       (try
         (let [result# (do ~@body)]
           (shared/validate-all-call-counts)
           result#)
         (finally
           (reset! shared/*call-counts* {})
           (reset! shared/*expected-counts* {}))))))

(defmacro with-global-http-stub-in-isolation
  "Makes all wrapped http-kit requests first match against given routes.
  If no route matches, an exception is thrown."
  [routes & body]
  `(with-redefs [shared/*in-isolation* true]
     (with-global-http-stub ~routes ~@body)))
