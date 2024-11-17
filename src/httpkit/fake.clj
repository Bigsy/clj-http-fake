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

(defn- matches-url [url request]
  (cond
    (instance? Pattern url) (re-matches url (:url request))
    (string? url) (= url (:url request))
    :else false))

(extend-protocol RouteMatcher
  String
  (matches [address _ request]
    (matches-url address request))

  Pattern
  (matches [address _ request]
    (matches-url address request))

  clojure.lang.PersistentArrayMap
  (matches [address _ request]
    (every? (fn [[k matcher]]
              (cond
                (instance? Pattern matcher) (re-matches matcher (str (get request k)))
                (instance? clojure.lang.IFn matcher) (matcher (get request k))
                :else (= matcher (get request k))))
            address))
            
  clojure.lang.PersistentVector
  (matches [address _ request]
    (let [[req-method url] address]
      (and (= (:method request) req-method)
           (matches-url url request)))))

(defn normalize-request-map [request]
  (let [req (if (string? request) {:url request} request)]
    (merge {:method :get} req)))

(defn- find-matching-route [routes request]
  (first
    (for [[route-key response] routes
          :when (cond
                 (vector? route-key) (let [[method url] route-key]
                                     (and (= method (:method request))
                                          (matches-url url request)))
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
