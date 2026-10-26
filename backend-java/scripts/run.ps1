. "$PSScriptRoot/env.ps1"
mvn.cmd -q "-Dmaven.repo.local=$projectRoot/.m2" -f backend-java/pom.xml -DskipTests package
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
java -jar backend-java/target/sellerpilot-java-0.1.0.jar
exit $LASTEXITCODE
