## Native libraries

The project selects Java 25 LTS through jenv using `.java-version`. On macOS,
install the versioned Homebrew JDK and register it with jenv:

```sh
brew install openjdk@25
jenv add "$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
```

The application loads SQLite (`sqlite3`) and SlateDB (`slatedb_uniffi`) by
their standard names from the JVM library path. Configure
`JAVA_TOOL_OPTIONS` before starting Clojure; the application does not read
library paths from Duct configuration.

On macOS, run the one-shot setup after fetching dependencies:

```sh
sh scripts/init.sh
```

Then copy `.env.example` to `.env` and run `direnv allow` in the repository.
The checked-in `.envrc` loads `.env` and makes `JAVA_TOOL_OPTIONS` available to
Clojure commands. Include the repository directory for
`libslatedb_uniffi.dylib` and the Homebrew SQLite library directory in
`-Djava.library.path`.

The development container provides both native libraries under
`/usr/local/lib` and sets the JVM library path in `Dockerfile.dev`.
