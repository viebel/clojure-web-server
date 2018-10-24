# Clojure Web Server

A demo of a Catacumba web server buit with components.

## Installation

~~~bash
lein deps
~~~

## Run locally

First you need to make sure that mongodb is installed locally.

Then:
~~~bash
lein run -m clojure-web-server.core
~~~

If it went well, you should be able to query the server 

~~~bash
curl http://localhost:8087/hello
hello
~~~

### Socket REPL 
A Socket REPL is automatically created on port 5555 (see `project.clj`). 
You can connect to the running server via `telnet` or `nc`  and modify the code while the server is running.

~~~bash
nc localhost 5555
user=> (in-ns 'clojure-web-server.web-server)
#object[clojure.lang.Namespace 0x11980216 "clojure-web-server.web-server"]
clojure-web-server.web-server=> (defn hello-world [_] "Good bye")
~~~

Now, if you query again the server you will get a modified response.

~~~bash
curl http://localhost:8087/hello
hello
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

