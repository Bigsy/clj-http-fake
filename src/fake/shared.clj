(ns fake.shared
  (:require [clojure.math.combinatorics :refer [cartesian-product permutations]]
            [clojure.string :as str]
            [ring.util.codec :as ring-codec]))

(def ^:dynamic *fake-routes* {})
(def ^:dynamic *in-isolation* false)
(def ^:dynamic *call-counts* (atom {}))
(def ^:dynamic *expected-counts* (atom {}))

(defn normalize-path [path]
  (cond
    (nil? path) "/"
    (str/blank? path) "/"
    (str/ends-with? path "/") path
    :else (str path "/")))

(defn defaults-or-value
  "Given a set of default values and a value, returns either:
   - a vector of all default values (reversed) if the value is in the defaults
   - a vector containing just the value if it's not in the defaults"
  [defaults value]
  (if (contains? defaults value) (reverse (vec defaults)) (vector value)))

(defn query-params-match?
  "Checks if the actual query parameters in a request match the expected ones.
   Works with both query-string and query-params formats, and handles both
   httpkit and clj-http parameter styles."
  [expected-query-params request]
  (let [actual-query-params (or (when-let [params (:query-params request)]
                                 (if (map? params) 
                                   (into {} (for [[k v] params] [(name k) (str v)]))
                                   (into {} (for [[k v] params] [(name k) (str v)]))))
                               (some-> request :query-string ring-codec/form-decode)
                               {})
        expected-query-params (into {} (for [[k v] expected-query-params] 
                                       [(name k) (str v)]))]
    (and (= (count expected-query-params) (count actual-query-params))
         (every? (fn [[k v]]
                  (= v (get actual-query-params k)))
                expected-query-params))))

(defn validate-all-call-counts []
  (doseq [[route-key expected-count] @*expected-counts*]
    (let [actual-count (get @*call-counts* route-key 0)]
      (when (not= actual-count expected-count)
        (throw (Exception. (format "Expected route '%s' to be called %d times but was called %d times"
                                 route-key expected-count actual-count)))))))