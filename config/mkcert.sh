#!/bin/sh

openssl req -x509 -nodes -days 730 -newkey rsa:3072 -keyout cert.key -out cert.pem -config req.cnf -sha256
openssl pkcs12 -export -in cert.pem -inkey cert.key -out keystore.pkcs12 -name tlskey

keytool -importkeystore -deststorepass secret -destkeypass secret -destkeystore keystore.jks \
        -srckeystore keystore.pkcs12 -srcstoretype PKCS12 -srcstorepass secret -alias tlskey