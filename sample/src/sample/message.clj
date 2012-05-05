(ns
    ^{:doc "Messaging Kiln"
      :author "Jeffrey Straszheim"}
  sample.message
  (use [sample logon-logoff utils]
       kiln.kiln
       kiln-ring.request
       slingshot.slingshot
       hiccup.core)
  (require [sample.message-database :as md]))


(defclay message-id
  :value (-> (ns-resolve *ns* 'sample.dispatch/main-dispatch) ; circular includes
             deref
             ??
             :message-id))

(defclay my-message?
  :value (let [{:keys [owner]} (md/get-message (?? message-id))
               current-user (?? current-user-name)]
           (= owner current-user)))

(defglaze require-my-message
  :operation (if (?? my-message?)
               (?next)
               (throw+ {:type :error-page
                        :message "Wrong User"})))

(defclay list-messages-body
  :glaze [require-logged-on]
  :value (let [ml (md/get-message-list)]
           (html
            (if (seq ml)
              [:ul.messages
               (for [{:keys [key owner header]} ml]
                 [:li
                  [:p.header (h header)]
                  [:p.owner (h owner)]
                  [:p.link
                   [:a {:href (->
                               (?? uri-with-path (format "/show-message/%s" key))
                               str)}
                    "(show message)"]]])]
              [:p.message "Sorry, no messages found"])
            [:p [:a {:href (str (?? uri-with-path "/new-message"))}
                 "(new message)"]])))


(defclay show-message-body
  :glaze [require-logged-on]
  :value (let [{:keys [key owner header content]} (md/get-message (?? message-id))]
           [:table
            [:tr [:th.header (h header)]]
            [:tr [:td.owner (h owner)]]
            [:tr [:td.content (h content)]]
            (when (?? my-message?)
              [:tr [:td.link [:a {:href (->
                                         (?? uri-with-path
                                             (format "/edit-message/%s" key))
                                         str)}
                              "(edit message)"]]])]))
           
  

(defclay new-message-body
  :glaze [require-logged-on]
  :value (html
          [:form {:action (str (?? uri-with-path "/new-message"))
                  :method "post"}
           [:p "Header"]
           [:p [:input {:type "text" :name "header"}]]
           [:p "Body"]
           [:p [:textarea {:name "body"}]]
           [:p [:input {:type "submit"}]]]))

(defclay edit-message-body
  :glaze [require-logged-on
          require-my-message]
  :value (let [{:keys [key owner header content]} (md/get-message (?? message-id))]
           [:form {:method "post"}
            [:p "Header"]
            [:p [:input {:type "text" :name "header" :value header}]]
            [:p "Body"]
            [:p [:textarea {:name "body"}
                 (h content)]]
            [:p [:input {:type "submit"}]]]))

(defclay new-message-action!
  :glaze [require-logged-on]
  :value (let [{:keys [body header]} (?? params)
               current-user-name (?? current-user-name)]
           (md/put-message current-user-name header body)))

(defclay new-message-redirect-uri
  :value (?? list-messages-uri))

(defclay edit-message-action!
  :glaze [require-logged-on
          require-my-message]
  :value (let [{:keys [body header]} (?? params)
               current-user-name (?? current-user-name)]
           (md/edit-message (?? message-id) header body)))

(defclay edit-message-redirect-uri
  :value (?? uri-with-path (format "/show-message/%s" (?? message-id))))

;; End of file
