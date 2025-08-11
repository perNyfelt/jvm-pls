Reactor build:

```shell
mvn -q -DskipTests install
mvn -q test
```

Run just plugin tests:

```shell
mvn -pl jvmpls-java -Dtest='*JavaPlugin*Test' test
mvn -pl jvmpls-groovy -Dtest='*GroovyPlugin*Test' test
```

Integration only:

```shell
 mvn -pl jvmpls-it -Dit.test='*IT' verify
```