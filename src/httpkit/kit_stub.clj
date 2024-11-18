(ns httpkit.kit_stub
  (:import (clojure.lang PersistentArrayMap PersistentVector)
           [java.util.regex Pattern])
  (:require [org.httpkit.client :as http]
            [ring.util.codec :as ring-codec]
            [clojure.math.combinatorics :refer :all]
            [stub.shared :as shared]
            [clojure.string :as str]))

(defn- potential-uris-for [request-map]
  (let [uri (:uri request-map)]
    (if (str/blank? uri)
      ["/" "" nil]
      [(shared/normalize-path uri) (str/replace uri #"/+$" "")])))

(defn- normalize-url-for-matching [url]
  (str/replace url #"/+$" ""))

(defn- matches-url [url request]
  (let [parsed-url (if (string? url) (shared/parse-url url) url)
        req-map (shared/parse-url (:url request))
        req-str (normalize-url-for-matching (shared/address-string-for req-map))]
    (cond
      (instance? Pattern url) 
      (re-matches url req-str)
      :else 
      (= (normalize-url-for-matching (shared/address-string-for parsed-url))
         req-str))))

(defn normalize-request-map [request]
  (let [req (if (string? request) 
              {:url request} 
              request)]
    (merge {:method :get} req)))

(defn- find-matching-route [routes request]
  (first
    (for [[url handlers] routes
          :when (matches-url url request)
          :let [method (:method request)
                handler (or (get handlers method)
                          (get handlers :any))]
          :when handler]
      [url (fn [req] 
            (handler (merge req 
                          {:url (:url request)
                           :method method
                           :query-params (:query-params request)})))])))

(defn- create-response [response request]
  (merge {:status 200
          :headers {}
          :body ""}
         (if (fn? response)
           (response request)
           response)))

(defn wrap-request-with-stub [client]
  (fn [req callback]
    (let [request (normalize-request-map req)
          matching-route (find-matching-route shared/*stub-routes* request)
          [url response] matching-route]
      (when url
        (swap! shared/*call-counts* update-in [url (:method request)] (fnil inc 0)))
      (let [response-promise (promise)]
        (if matching-route
          (deliver response-promise (create-response response request))
          (if shared/*in-isolation*
            (throw (Exception. (str "No matching stub route found for " (:method request) " "
                                  (:url request))))
            (client req #(deliver response-promise %))))
        (callback @response-promise)
        response-promise))))

(defmacro with-http-stub
  "Makes all wrapped http-kit requests first match against given routes.
   Routes should be in the format:
   {\"http://example.com\" 
    {:get (fn [req] {:status 200})
     :post (fn [req] {:status 201})
     :any (fn [req] {:status 200})}}"
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
