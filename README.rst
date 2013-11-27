=================
supervisord-crate
=================

**supervisord-crate** is a Pallet_ crate for installing and running
supervisord_ to supervise processes. It also registers with the Pallet
supervision service provider interface, so crates which request process
supervision can be configured to run under supervisord.

.. _Pallet: http://palletops.com
.. _supervisord: http://supervisord.org

Status
======

Very alpha--no releases yet.

Currently ``supervisord-crate`` is known to work against Debian 6 and 7 nodes,
using the ``:packages`` install strategy. There is also an install strategy for
getting supervisord via PIP, but it is less thoroughly tested.

Usage
=====

This is an example of consuming the supervisord SPI from an
``app-deploy-crate``.

Extend ``pallet.crate.service/supervisor-config-map`` over the kind of crate
you wish to deploy (in this example, ``:app-deploy``) to generate a suitable
service configuration for ``:supervisord`` to supervise it:

.. code:: clojure

  (require '[pallet.crate.app-deploy :as app-deploy]
           '[pallet.crate.java :as java]
           '[pallet.crate.service :as sup]
           '[pallet.crate.supervisord :as supervisord]
           '[pallet.api :as api])

  (defmethod sup/supervisor-config-map [:app-deploy :supervisord]
    [_ {:keys [run-command service-name user] :as settings} options]
    {:pre [service-name]}
    (merge {:service-name service-name
            :command run-command
            :autostart "true"
            :autorestart "true"
            :startsecs 10}
           (when user {:user user})))

Build an ``app-deploy`` ``server-spec`` and request supervisord as the
supervision provider:

.. code:: clojure

  (defn app-server [port]
    (app-deploy/server-spec
      {:artifacts
       {:from-lein
        [{:project-path "target/my-app-%s-standalone.jar"
          :path "example-app/app.jar"}]}
       :run-command (str "java -jar /opt/example-app/app.jar " port)
       :supervisor :supervisord
       :user "supervisord"}
      :instance-id :example-app))

Finally, define your ``group-spec`` to pull in supervisor itself and your
service:

.. code:: clojure

  (defn web-group []
    (api/group-spec
      "web"
      :extends [(java/server-spec {})
                (app-server 8080)
                (supervisor/server-spec {})]
      :node-spec (api/node-spec :image {:os-family :debian})))

Limitations and future work
===========================

* No tests. Still working out exactly how to test around Pallet's magic.

* Getting the supervisor itself to run as a different user requires a lot of
  fighting against the package manager, and so the half-broken support for that
  has been removed. This may suck less with the PIP install strategy, but needs
  to be tested. *Note that you can still ask supervisord to run your services
  as other users just fine.*

* Totally untested on any images other than Debian 6 and 7. The config paths
  and stuff are probably all wrong for other distributions.

License
=======

``supervisord-crate`` is Copyright (C) 2013 Kyle Schaffrick.

Distributed under the Eclipse Public License.
