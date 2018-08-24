# Clojure Web Server

A demo of a Catacumba web server buit with components.

## Installation

~~~bash
lein deps
~~~
p
## Run locally

~~~bash
lein run -m clojure-web-server.core
~~~

## Run locally inside a REPL
~~~bash
lein repl
user => (go)
~~~

When you want to restart the server (and automatically reload modified files)
~~~
user => (reset)
~~~

When you want to stop the server (and automatically reload modified files)
~~~
user => (stop)
~~~



## Run as a jar

~~~bash
lein uberjar
java -jar target/uberjar/clojure-web-server-standalone.jar
~~~

