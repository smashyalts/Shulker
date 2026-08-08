# Overriding Pod properties

::: tip Reaching anything the fields below do not cover

`podOverrides` models a fixed set of fields. For everything else — topology
spread constraints, priority classes, termination grace periods, sidecar
containers, custom probes — use `podOverrides.podTemplate`, a free-form
`PodTemplateSpec` overlay merged onto the one Shulker generates.

The merge follows Kubernetes strategic merge patch semantics: objects merge key
by key, lists whose entries carry a `name` merge by that name, everything else
is replaced, and an explicit `null` removes a field. So this adds an environment
variable and a spread constraint without discarding anything Shulker generated:

```yaml
podOverrides:
  podTemplate:
    spec:
      priorityClassName: game-critical
      terminationGracePeriodSeconds: 120
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels:
              app.kubernetes.io/name: minecraft-server
      containers:
        - name: minecraft-server # must match, this is the merge key
          env:
            - name: MY_EXTRA_VAR
              value: '1'
```

The main container is named `minecraft-server` on servers and `proxy` on
proxies; the init container is `init-fs`. `podTemplate` is applied after the
individual overrides, so it wins wherever both set the same field.

The overlay is stored as an opaque object, so the API server does not validate
it against the pod schema — a typo surfaces when the operator reconciles rather
than at `kubectl apply` time.

:::

To suit your needs, you may need to override or complete some
properties filled to the underlying Pod. The `podOverrides`
property of both the `ProxyFleet` and `MinecraftServerFleet`
allows you to customize the Pod properties as you were writing
a Pod directly (i.e. the supported sub-properties are identical
to the Pod specification).

You can find all the properties overridable by looking at the
`shulker-crds` package of the repository:

- [ProxyFleet](https://github.com/jeremylvln/Shulker/blob/main/packages/shulker-crds/src/v1alpha1/proxy_fleet.rs)
- [MinecraftServerFleet](https://github.com/jeremylvln/Shulker/blob/main/packages/shulker-crds/src/v1alpha1/minecraft_server_fleet.rs)

## Adding environment variables

Shulker already injects some environment variables that could
be useful. But adding your own is fully supported:

```yaml
apiVersion: shulkermc.io/v1alpha1
kind: MinecraftServerFleet
metadata:
  name: my-server
spec:
  clusterRef:
    name: my-cluster
  replicas: 1
  template:
    spec: // [!code focus]
      podOverrides: // [!code focus]
        env: // [!code focus]
          - name: OPENMATCH_HOST // [!code focus]
            value: open-match-frontend.open-match.svc // [!code focus]
          - name: OPENMATCH_PORT // [!code focus]
            value: '50504' // [!code focus]
```

## Setting custom affinities

By default, Agones adds a _preferred_ scheduling on nodes
labelled with `agones.dev/role=gameserver`. However you
may want to customize more the scheduling behavior.

For instance, you may want to restrict some servers to some
nodes:

```yaml
apiVersion: shulkermc.io/v1alpha1
kind: MinecraftServerFleet
metadata:
  name: my-server
spec:
  clusterRef:
    name: my-cluster
  replicas: 1
  template:
    spec: // [!code focus]
      podOverrides: // [!code focus]
        affinity: // [!code focus]
          nodeAffinity: // [!code focus]
            requiredDuringSchedulingIgnoredDuringExecution: // [!code focus]
              nodeSelectorTerms: // [!code focus]
                - matchExpressions: // [!code focus]
                    - key: devops.example.com/gameserver // [!code focus]
                      operator: In // [!code focus]
                      values: // [!code focus]
                        - my-server // [!code focus]
        tolerations: // [!code focus]
          - key: "devops.example.com/gameserver" // [!code focus]
            operator: "Equal" // [!code focus]
            value: "my-server" // [!code focus]
            effect: "NoSchedule" // [!code focus]
```

## Mounting volumes <Badge type="tip" text="servers" />

Additional volumes can be injected to the `MinecraftServer`'s
created `Pod`:

```yaml
apiVersion: shulkermc.io/v1alpha1
kind: MinecraftServer
metadata:
  name: my-server
spec:
  clusterRef:
    name: my-cluster
  podOverrides: // [!code focus]
    volumeMounts: // [!code focus]
      - name: my-extra-volume // [!code focus]
        mountPath: /mnt/path // [!code focus]
    volumes: // [!code focus]
      - name: my-extra-volume // [!code focus]
        emptyDir: {} // [!code focus]
```

:::warning

Agones, and thus Shulker, are not meant for data persistence but
rather ephemeral workload. While adding custom volumes to a `MinecraftServer`
is expected to work perfectly, adding some to a `MinecraftServerFleet`
will only work if your volume source support multiple mounts (it is
essentially the same as mounting the same volume to a `Deployment`).

:::
