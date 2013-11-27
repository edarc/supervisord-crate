(ns pallet.crate.supervisord
  "A pallet crate to install and configure supervisord."
  (:require [clojure.string
             :refer [join split]]
            [clojure.tools.logging
             :refer [debugf warnf]]
            [pallet.actions :as actions]
            [pallet.api :as api]
            [pallet.crate :as crate
             :refer [defplan defmethod-plan]]
            [pallet.crate-install :as crate-install]
            [pallet.crate.service :as sup]
            [pallet.script.lib :as sl]
            [pallet.stevedore :as sh]
            [pallet.utils :as utils]
            [pallet.versions :as versions]
            [pallet.version-dispatch
             :refer [defmethod-version-plan defmulti-version-plan]]))

;;; Settings

(defmulti-version-plan default-settings [version])

(defmethod-version-plan default-settings {:os :debian}
  [os os-version version]
  {:user "supervisord"
   :install-strategy :packages
   :packages ["supervisor"]
   :conf-path (sh/fragment
                (sl/file (sl/config-root) "supervisor" "supervisord.conf"))
   :supervisord-config
   {:logfile (sh/fragment (sl/file (sl/log-root) "supervisor" "supervisord.log"))
    :childlogdir (sh/fragment (sl/file (sl/log-root) "supervisor"))
    :loglevel :info
    :pidfile (sh/fragment (sl/file (sl/pid-root) "supervisord.pid"))
    :directory nil
    :unix-socket (sh/fragment
                   (sl/file (sl/state-root) "supervisor" "supervisord.sock"))}})
   

(defplan settings-plan [{:keys [version instance-id] :as settings}]
  (let [settings (utils/deep-merge (default-settings version) settings)]
    (crate/assoc-settings :supervisord settings {:instance-id instance-id})))

;;; User/group

(defplan user [{:keys [] :as options}]
  (let [{:keys [user group]}
        (crate/get-settings :supervisord options)]
    (debugf "Create supervisord user %s group %s" user group)
    (actions/user user :system true :shell :false)))

;;; Installation

(defmulti-version-plan install-prereq [version])

(defmethod-version-plan install-prereq {:os :debian}
  [os os-version version]
  (actions/package "python-pip"))

(defmethod-plan crate-install/install ::pip
  [facility instance-id]
  (let [{:keys [packages] :as settings}
        (crate/get-settings :supervisord {:instance-id instance-id})]
    (actions/exec-checked-script
      "Install pip packages"
      ("pip" "install" ~@packages))))

(defplan install [{:keys [instance-id]}]
  (let [{:keys [user] :as settings}
        (crate/get-settings :supervisord {:instance-id instance-id})
        logdir (get-in settings [:supervisord-config :childlogdir])]
    (crate-install/install :supervisord instance-id)
    (actions/directory logdir :action :create :owner user :mode "755")))

;;; Register service supervisor

(defmethod sup/service-supervisor-available? :supervisord [_] true)

(defmethod sup/service-supervisor-config :supervisord
  [_ {:keys [service-name command] :as service-config} options]
  (crate/update-settings :supervisord options
                         assoc-in [:jobs (keyword service-name)] service-config))

(defmethod sup/service-supervisor :supervisord
  [_
   {:keys [service-name]}
   {:keys [action if-flag if-stopped instance-id wait]
    :or {action :start wait true}
    :as options}]
  (debugf "Controlling service %s, action %s" service-name action)
  (cond
    (#{:start :stop :restart} action)
    (actions/exec-checked-script
      (str "supervisord: " (name action) " " service-name)
      ("supervisorctl" action service-name))))

;; Config file generation

(defn format-keyval-config [pairs]
  (let [underscore (fn [s] (.replace s \- \_))
        format-pair (fn [[k v]] (str (underscore (name k)) "=" v))
        lines (map format-pair pairs)]
    (join "\n" lines)))

(defn format-service-config [service service-config]
  (let [santiary-service-name (.. (name service)
                                  (replace \: \-)
                                  (replace \[ \-)
                                  (replace \] \-))
        heading (str "[program:" santiary-service-name "]")]
    (str heading "\n" (format-keyval-config service-config))))

(defn format-config [settings]
  (let [s (:supervisord-config settings)
        supervisord-config {:logfile (:logfile s)
                            :loglevel (name (:loglevel s))
                            :childlogdir (:childlogdir s)
                            :pidfile (:pidfile s)}
        supervisord-config (if-let [dir (:directory s)]
                             (assoc supervisord-config :directory dir)
                             supervisord-config)
        apply-user (fn [cfg]
                     (let [cfg (merge {:user (:user settings)} cfg)]
                       (if (:user cfg) cfg (dissoc cfg :user))))]
    (str "[supervisord]\n"
         (format-keyval-config supervisord-config) "\n\n"
         "[unix_http_server]\n"
         (format-keyval-config {:file (:unix-socket s)}) "\n\n"
         "[supervisorctl]\n"
         (format-keyval-config {:serverurl (str "unix://" (:unix-socket s))}) "\n\n"
         (join "\n\n" (map (fn [[s c]] (format-service-config s (apply-user c))) (:jobs settings))))))

(defn configure [{:keys [instance-id] :as options}]
  (let [settings (crate/get-settings :supervisord {:instance-id instance-id})
        config-content (format-config settings)]
    (debugf "Writing config (%s jobs)" (count (:jobs settings)))
    (actions/remote-file (:conf-path settings)
                         :literal true
                         :overwrite-changes true
                         :content config-content)
    (actions/service "supervisor" :action :stop)
    (actions/service "supervisor" :action :start)))

;;; Server spec

(defn server-spec [settings & {:keys [instance-id] :as options}]
  (let [settings (merge settings options)]
    (api/server-spec
      :phases {:settings (api/plan-fn
                           (settings-plan settings))
               :install (api/plan-fn
                          (user options)
                          (install options))
               :configure (api/plan-fn
                            (configure options))})))
