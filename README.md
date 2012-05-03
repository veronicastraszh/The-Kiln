# The Kiln
  
The Kiln is designed to make large complex functions easier to
write. By "large and complex", I mean things such as web applications,
where you do not compute a single, simple result for a well-defined
input, but instead a very complex set of outputs along with
side-effects.

Consider a standard storefront application. When the user adds an item
to the cart, the system must do more than just one simple database
operation, and it must return more than "success." In a real webapp,
we get steps that look more like this:

* Check a variety of security parameters
* Validate the input
* Perform the update to the database
* Query the database to get the new cart
* Iterate through various "user widgets" and update their state, such
  as ad banners, content for the various side columns, twitter
  updates, etc.
* Add any analytics controls to the page
* Pick a template, render it

And so on. And this only counts the user-visible business logic. A
complex webapp might also have a variety of logging and monitoring
code that must be run on each request.

In my experience, the size of such applications seems to grow linearly
with time, as product managers dream up more features to add and more
complex ways for them to interact. Such as, "Hey, now that you've
added a reward points system, can we hook it into the product
recommendation system, so that the products with eligible bonuses
appear higher?"

The list of product demands will go on. The complexity of the
application will spiral out of control.

Here are some specific complexity issues that I've found:

* For each request, there is a large set of data that needs to be
  recomputed, such as (perhaps) a user-id, his session, a database
  connection, validation parameters, logging parameters, search
  results, other kinds of results, and so on. I've found two things
  true about this mass of data:
    - Most of these items are computed zero or one times for each
      request.
    - Often the data you need in one part of the control flow isn't
      visible because it is created and consumed elsewhere.
    - What you need to compute tends to not change much, but how you
      compute it often does. That is, new bits of data become
      relevant, requiring ever-growing argument lists (or worse, the
      heavy dependence of dynamic scope).

* The classic problem, "I need this here, but I have to thread it
  through a dozen functions to get it," becomes legion. Dynamic
  binding can help, but it creates its own maintenance nightmare.

* Business logic gets mixed up with security, logging, analytics,
  etc. Aspect oriented programming can help, but creates its own
  maintenance headaches as a never-ending stream of new "aspect"
  types gizmos are added to the application to handle the needed
  control.

* Control flow gets really tricky and non-obvious.

In theory lots of strong engineering vision, constant refactoring, and
an unearthly level of insight at the start can avoid many of these
problems. However, in the real world, I've never been that lucky. For
me, real-world, fast-changing, competitive applications become a big
ball of mud.

The Kiln is designed to cook up that big ball of mud into something
manageable. It is built around these principles:

* The key values in the application are represented by top-level
  objects called "clay".

* Clays themselves are stateless. For each request/invocation/etc.,
  the value of each clay is computed within a first-class environment,
  called a "kiln". You can create and destroy kilns at will. (In a
  webapp, the idea is that you create one kiln per request.)

* Clays are computed lazily.

* Clays know how to cleanup after themselves. If your database
  connection is a clay, it will get closed at the end of the request.

* No dynamic scope. It will allow you to work with libraries that
  require dynamic scoping, but no adding your own.

* No aspects. Clays can be wrapped with "glaze", which replaces the
  needs for aspects. Unlike aspects, glazes are visible where the clay
  is defined.

* Clays should off-load their computation to normal functions.
  However, those functions should be as simple as possible, and do
  exactly one thing. (Let all the crazy, ugly dependencies live in the
  kiln.)



## Usage

    ;; To start with, you must create some "coal". Coals are your base
    ;; values, the ones not computed from other things in the kiln. For a
    ;; webapp, we'll use the request object.
    
    (defcoal request "The request object")
    
    ;; Don't worry that it's blank. We'll assign a value later.
    
    ;; So, now we can compute stuff from that.
    
    (defclay uri
      "The URI of the request"
      :value (:uri (?? request)))
    
    ;; Note the ?? syntax. It will automaticall lookup the request.
    
    (defclay path
      "The path"
      :value (.getPath (?? uri)))
    
    ;; Dispatching is one of the first things you do in most
    ;; frameworks. For now, we just dispatch on path.
    
    (defclay dispatch
      "What do I do?"
      :value (condp = (?? path)
               "/remove-user" :remove-user
               "/add-user" :add-user
               "/view-user" :view-user))
    
    ;; Of course, as the application grows, dispatching will get more
    ;; complex. No problem, just make the clay look at more stuff.
    
    ;; We must match the dispatch to an action
    
    (declare log)
    (declare security-check)
    (declare db-con)
    (declare user)
    
    (defclay add-user!
      :glaze [(log :info)
              security-check]
      :value (add-user (?? db-con) (?? user)))
    
    (defclay remove-user!
      :glaze [(log :info)
              security-check]
      :value (remove-user (?? db-con) (?? user)))
    
    (defclay action!
      "Dispatch to the action to perform"
      :glaze [(log :debug)]
      :value (condp = (?? dispatch)
               :remove-user (?? remove-user!)
               :add-user (?? add-user!)
               :view-user nil ; no changes!
               ))
    
    ;; Here I'm assuming that the functions add-user and remove-user are
    ;; already defined somewhere.
    
    ;; So, we need a database connection.
    
    (defclay db-con
      :value (get-db-con)
      :cleanup (close-db-con ?self))
    
    ;; If you wanted to automatically manage transactioning, try this:
    
    (defclay transactioned-db
      :value (get-transactioned-connection)
      :cleanup-success (do (commit ?self)
                           (close-db-con ?self))
      :cleanup-failure (do (rollback ?self)
                           (close-db-con ?self)))
    
    ;; I won't detail user, but you can figure it out.
    
    ;; Let's look at glaze
    
    (defglaze log
      "Log an operation"
      :args [level]
      :operation (let [clay-name (str (:name ?clay))]
                   (log level (format "Begin %s" clay-name))
                   (let [result (?next)]
                     (log level (format "Complete %s result: %s"
                                        clay-name
                                        (str result)))
                     result)))
    
    ;; Of course, in a real application that would become more detailed.
    
    (defglaze security-check
      "Throw if user not valid"
      :operation (if-not (valid-user? (?? user))
                   (throw+ {:type :security-breach!
                            :user (?? user)
                            :clay ?clay})
                   (?next)))
    
    ;; Of course, we need a view:
    
    (defclay template
      :value (condp = (?? dispatch)
               :remove-user "templates/logon"
               :add-user "templates/show-user"
               :view-user "templates/show-user"))
    
    ;; And so on.
    
    ;; What does the driver look like:
    
    (defn run-kiln
      [req]
      (let [kiln (new-kiln)]
        (stoke-coal kiln request req)
        (let [result (try
                       (fire kiln action!) ; do the stuff
                       (render-template (fire kiln template) ...other kiln data...)
                       (catch Exception e
                         (cleanup-kiln-failure kiln)
                         (throw e)))]
          (cleanup-kiln-success kiln)
          result)))
    
    ;; So, at this point software engineer types will be pointing out that
    ;; some of our clays (namely dispatch, action!, and template) are too
    ;; interdependent.  True. Let's try a minor refactor:
    
    (defclay dispatch-structure
      :value (condp = (?? path)
               "/remove-user" {:name :remove-user
                               :action! add-user!
                               :template "templates/logon"}
               "/add-user" {:name :add-user
                            :action! remove-user!
                            :template "templates/show-user"}
               "/view-user" {:name :view-user
                             :action! nil
                             :templates "templates/show-user"}))
    
    (defclay dispatch ; we're redefining this
      :value (:name (?? dispatch-structure)))
    
    (defclay action!
      :glaze [(log :debug)]
      :value (when-let [action-clay! (:action! (?? dispatch-structure))]
               (?? action-clay!)))
    
    (defclay template
      :value (:template (?? dispatch-structure)))
    
    ;; Nice, eh?


## Mixing Clays With Transactions

By default, a clay cannot be evaluated with a dosync block. So code
like this will not work:

    (dosync (fire some-kiln some-clay))

This also will fail:

    (defclay some-clay
      :value (let [value (dosync (?? another-clay))]
                (something value)))

The reason for this is because the actual firing of the clays, and the
internal maintainance of the kiln themselves use transactions. If they
are wrapped within a dosync, there seems a high likelyhood that
rollbacks will leave clays getting computed multiple times, thus
causing errors in the side effects. It seems wise to just dissallow it
by default.

However, there may be times when you simply need a series of clays to
be transactional. In this case, they can be marked
`transaction-allowed?`, which will override this behavior.

    (defclay some-transactioned-clay
      :value (do-something (?? another-clay))
      :transaction-allowed? true)

Now `(dosync (fire some-kiln some-transactioned-clay))` will
work. Note, however, that another-clay must also be thus defined.
    
    
## TODO

* Dynamic Glaze.
* Cleanup for Glaze.

## License

Copyright (C) 2012 Jeffrey Straszheim

Distributed under the Eclipse Public License, the same as Clojure.