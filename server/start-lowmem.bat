@echo off
chcp 65001 >nul

:loop
C:\graalvm17\bin\java.exe -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Xms96m -Xmx768m -Xss256k -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=128m -XX:ReservedCodeCacheSize=64m -XX:CompressedClassSpaceSize=32m -XX:StringTableSize=1009 -XX:-UsePerfData -XX:+UseSerialGC -XX:+UseStringDeduplication -XX:+OptimizeStringConcat -XX:+ExitOnOutOfMemoryError -jar Server-All.jar

timeout /t 5 /nobreak
goto loop
