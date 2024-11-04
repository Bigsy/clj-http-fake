# clj-http-fake 
[![MIT License](https://img.shields.io/badge/license-MIT-brightgreen.svg?style=flat)](https://www.tldrlegal.com/l/mit) 
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.bigsy/clj-http-fake.svg)](https://clojars.org/org.clojars.bigsy/clj-http-fake)

This is a fork of clj-http.fake, which is a library for stubbing out clj-http requests. This version has added
functionality for counting the number of calls made to a particular url.
## Usage

```clojure
(ns myapp.test.core
  (:require [clj-http.client :as c])
  (:use clj-http.fake))
```

The public interface consists of macros:

* ``with-fake-routes`` - lets you override clj-http requests that match keys in the provided map
* ``with-fake-routes-in-isolation`` - does the same but throws if a request does not match any key
* ``with-global-fake-routes``
* ``with-global-fake-routes-in-isolation``

'Global' counterparts use ``with-redefs`` instead of ``binding`` internally so they can be used in
a multi-threaded environment.

### Examples

```clojure
(with-fake-routes
  {;; Exact string match:
   "http://google.com/apps"
   (fn [request] {:status 200 :headers {} :body "Hey, do I look like Google.com?"})
   ;; matches (c/get "http://google.com/apps")

   ;; Exact string match with query params:
   "http://google.com/?query=param"
   (fn [request] {:status 200 :headers {} :body "Nah, that can't be Google!"})
   ;; matches (c/get "http://google.com/" {:query-params {:query "param"}})

   ;; Regexp match:
   #"https://([a-z]+).packett.cool"
   (fn [req] {:status 200 :headers {} :body "Hello world"})
   ;; matches (c/get "https://labs.packett.cool"),
   ;; (c/get "https://server.packett.cool") and so on, based on
   ;; regexp.

  ;; Match based an HTTP method:
   "http://shmoogle.com/"
   {:get (fn [req] {:status 200 :headers {} :body "What is Scmoogle anyways?"})}
   ;; will match only (c/get "http://google.com/")

   ;; Match multiple HTTP methods:
   "http://doogle.com/"
   {:get    (fn [req] {:status 200 :headers {} :body "Nah, that can't be Google!"})
    :delete (fn [req] {:status 401 :headers {} :body "Do you think you can delete me?!"})}

   ;; Match using query params as a map
   {:address "http://google.com/search" :query-params {:q "aardark"}}
   (fn [req] {:status 200 :headers {} :body "Searches have results"})

   ;; If not given, the fake response status will be 200 and the body will be "".
   "https://duckduckgo.com/?q=ponies"
   (constantly {})}

 ;; Your tests with requests here
 )
```

### Call Count Validation

You can now specify and validate the number of times a route should be called using the `:times` option:

```clojure
(with-fake-routes
  {"http://api.example.com/data"
   {:get {:handler (fn [_] {:status 200 :body "ok"})
          :times 2}}}
  
  ;; This will pass - route is called exactly twice as expected
  (c/get "http://api.example.com/data")
  (c/get "http://api.example.com/data"))

;; This will fail with "Expected route to be called 2 times but was called 1 times"
(with-fake-routes
  {"http://api.example.com/data"
   {:get {:handler (fn [_] {:status 200 :body "ok"})
          :times 2}}}
  (c/get "http://api.example.com/data"))

;; This will fail with "Expected route to be called 1 times but was called 2 times"
(with-fake-routes
  {"http://api.example.com/data"
   {:get {:handler (fn [_] {:status 200 :body "ok"})
          :times 1}}}
  (c/get "http://api.example.com/data")
  (c/get "http://api.example.com/data"))
```

The `:times` option allows you to:
- Verify a route is called exactly the expected number of times
- Ensure endpoints aren't called more times than necessary

If the actual number of calls doesn't match the expected count, an exception is thrown with a descriptive message. 
If `:times` not supplied it acts as any number of times.

## Development

Use [Leiningen](https://leiningen.org) **with profiles**. E.g.:

```sh
$ lein with-profile +latest-3.x,+1.8 repl
```

There are aliases to run the tests with the oldest and newest supported versions of clj-http:

```sh
$ lein test-3.x  # Testing under clj-http 3.x
$ lein test-2.x  # Testing under clj-http 2.x
```

## License

Released under [the MIT License](http://www.opensource.org/licenses/mit-license.php).
