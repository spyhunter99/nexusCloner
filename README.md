# Nexus Cloner

This tool will you let you clone an entire nexus repository

# Usage cases

If you don't have direct file system access to the nexus repository and need to clone it
If directory browsing is turned on
If the nexus repository requires either username/password or cookie based logins (or none at all)
If you are NOT cloning maven central (you'll get banned)

# How is it tested?

Primarily with v2.x of Sonatype OSS

# How do I use it?

The stuff in square brackets denoates an optional parameter, parentheses is an example value.

````
mvn clean install
java -jar target\nexusCloner-1.0-SNAPSHOT-jar-with-dependencies.jar -url (repo url) [-cookie] [-username] (username) [-password] (password) -index 
java -jar target\nexusCloner-1.0-SNAPSHOT-jar-with-dependencies.jar -url (repo url) [-cookie] [-username] (username) [-password] (password) -download [-threads] (4)
````

# License

ASF 2.0