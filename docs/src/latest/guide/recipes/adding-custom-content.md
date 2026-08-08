# Adding custom content

Proxies and servers could need custom content like maps, plugins, mods
or any other file.

## Ways of retrieving content

Additional content can be retrieved in several ways, all of them could
by used for any content.

### Direct URL

The most straightforward way of downloading files is via a direct URL.
It is attended that the URL is a direct link to the file, without any
authentication layer. Otherwise it should be specified directly in the
URL, thus exposing the credentials to the public.

```yaml
url: https://example.com/my-file.tar.gz
```

### Maven Repository

Most useful for JAR archives, Maven repositories can be used to download
files. The URL is composed of the repository URL, the group ID, the
artifact ID and the version. Optionally, one can specify a secret name
containing the credentials to use to authenticate agaisnt the repository.

```yaml
urlFrom:
  mavenRef:
    repositoryUrl: https://example.com/maven
    groupId: com.example
    artifactId: myplugin
    version: '1.0.0'
    credentialsSecretName: example-repo-secret
```

### Verifying integrity

Whatever the source, you can declare the expected SHA-256 digest of the file.
The init container verifies the download against it and refuses to start the
Pod if it does not match.

```yaml
url: https://example.com/my-plugin.jar
sha256: 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
```

It works the same way alongside `urlFrom`:

```yaml
urlFrom:
  mavenRef:
    repositoryUrl: https://example.com/maven
    groupId: com.example
    artifactId: myplugin
    version: '1.0.0'
sha256: 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
```

::: warning

`sha256` is optional, but you should set it. Everything downloaded this way is
executed inside your server Pod, so without a digest, whoever controls the URL
— or anyone who can tamper with the response — controls what runs in your
cluster. When it is omitted, the init container logs a warning.

Note that a `mavenRef` pointing at a `-SNAPSHOT` version resolves to whatever
the latest snapshot build is, so its digest changes over time; pin a release
version if you want to verify it.

:::

## Adding plugins <Badge type="tip" text="proxies" /> <Badge type="tip" text="servers" />

Shulker can automatically download plugins from different sources and
place them in the `plugins` folder of your proxy or server. It is
expected to download JAR files. They should not be packed in an archive.

Here is how to add two plugins, one using a direct URL, the other from
a Maven repository.

:::info

While the following example is a `MinecraftServerFleet`, the same applies
for a `ProxyFleet`.

:::

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
    spec:
      config: // [!code focus]
        plugins: // [!code focus]
          - url: https://example.com/my-plugin.jar // [!code focus]
          - urlFrom: // [!code focus]
              mavenRef: // [!code focus]
                repositoryUrl: https://example.com/maven // [!code focus]
                groupId: com.example // [!code focus]
                artifactId: myplugin // [!code focus]
                version: '1.0.0' // [!code focus]
                credentialsSecretName: example-repo-secret // [!code focus]
```

## Adding maps <Badge type="tip" text="servers" />

Some servers may need maps to be downloaded before the server is started.
This may be used for ephemeral servers using the same map, like minigames.

Shulker can download a tar-gzipped archive and extract it at the server
root directory.

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
    spec:
      config: // [!code focus]
        world: // [!code focus]
          url: https://example.com/my-worlds.tar.gz // [!code focus]
```

## Adding patches <Badge type="tip" text="proxies" /> <Badge type="tip" text="servers" />

Patches are tar-gzipped archives that are extracted at the root of the
server, overwriting existing files. They can be used to _patch_ the server
content. They are applied in the order specified in the configuration.

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
    spec:
      config: // [!code focus]
        patches: // [!code focus]
          - url: https://example.com/my-first-patch.tar.gz // [!code focus]
          - url: https://example.com/my-second-patch.tar.gz // [!code focus]
```

## Real-world example

You can find a real-world example of a `ProxyFleet` and `MinecraftServerFleet`
with additional content by looking at the `with-custom-configuration` example
**[from the repository](https://github.com/jeremylvln/Shulker/tree/main/examples/with-custom-configuration)**.
