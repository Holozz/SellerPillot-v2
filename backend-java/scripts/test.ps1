. "$PSScriptRoot/env.ps1"
$env:JAVA_IT_MYSQL_URL = 'jdbc:mysql://127.0.0.1:3310/sellerpilot_java_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
mvn.cmd -q "-Dmaven.repo.local=$projectRoot/.m2" -f backend-java/pom.xml test
exit $LASTEXITCODE
