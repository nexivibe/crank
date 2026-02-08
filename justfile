release_dir := "/home/ubuntu/releases"

# Build the fat JAR and copy all needed jars to the release directory
release: package
    mkdir -p {{release_dir}}
    cp target/crank.jar {{release_dir}}/
    cp target/bcprov-jdk18on-*.jar {{release_dir}}/
    cp target/bcpkix-jdk18on-*.jar {{release_dir}}/
    cp target/bcutil-jdk18on-*.jar {{release_dir}}/
    @echo "Released to {{release_dir}}:"
    @ls -lh {{release_dir}}/*.jar

# Run mvn package (fat JAR + BouncyCastle jars)
package:
    mvn package -q -DskipTests

# Compile only
compile:
    mvn compile -q

# Run the application
run:
    mvn javafx:run -q

# Run tests
test:
    mvn test

# Clean build artifacts
clean:
    mvn clean -q
