#!/bin/sh
set -eu
umask 077

mkdir -p /out/ca /out/server /out/client /out/client-other
if [ -f /out/ca/ca.crt ] && [ -f /out/ca/ca.key ] && [ -f /out/ca/ca.pem ] &&
   [ -f /out/server/server.crt ] && [ -f /out/server/server.key ] &&
   [ -f /out/client/client.p12 ] && [ -f /out/client/password.txt ] &&
   [ -f /out/client-other/client-other.p12 ] && [ -f /out/client-other/password.txt ]; then
    exit 0
fi

openssl req -x509 -newkey rsa:2048 -noenc -days 3650 \
    -subj /CN=ltv-mtls-ca -keyout /out/ca/ca.key -out /out/ca/ca.crt
cp /out/ca/ca.crt /out/ca/ca.pem
chmod 600 /out/ca/ca.key

openssl req -newkey rsa:2048 -noenc -subj /CN=localhost \
    -keyout /out/server/server.key -out /out/server/server.csr
printf 'subjectAltName=IP:127.0.0.1,DNS:localhost\nextendedKeyUsage=serverAuth\n' > /out/server/server.ext
openssl x509 -req -in /out/server/server.csr -CA /out/ca/ca.crt \
    -CAkey /out/ca/ca.key -CAcreateserial -days 3650 \
    -extfile /out/server/server.ext -out /out/server/server.crt

openssl req -newkey rsa:2048 -noenc -subj /CN=ltv-mtls-client \
    -keyout /out/client/client.key -out /out/client/client.csr
printf 'extendedKeyUsage=clientAuth\n' > /out/client/client.ext
openssl x509 -req -in /out/client/client.csr -CA /out/ca/ca.crt \
    -CAkey /out/ca/ca.key -CAcreateserial -days 3650 \
    -extfile /out/client/client.ext -out /out/client/client.crt
openssl rand -hex 16 > /out/client/password.txt
openssl pkcs12 -export -inkey /out/client/client.key -in /out/client/client.crt \
    -certfile /out/ca/ca.crt -out /out/client/client.p12 \
    -passout file:/out/client/password.txt

openssl req -x509 -newkey rsa:2048 -noenc -days 3650 \
    -subj /CN=ltv-mtls-other-ca \
    -keyout /out/client-other/ca.key -out /out/client-other/ca.crt
openssl req -newkey rsa:2048 -noenc -subj /CN=ltv-mtls-other-client \
    -keyout /out/client-other/client.key -out /out/client-other/client.csr
printf 'extendedKeyUsage=clientAuth\n' > /out/client-other/client.ext
openssl x509 -req -in /out/client-other/client.csr -CA /out/client-other/ca.crt \
    -CAkey /out/client-other/ca.key -CAcreateserial -days 3650 \
    -extfile /out/client-other/client.ext -out /out/client-other/client.crt
openssl rand -hex 16 > /out/client-other/password.txt
openssl pkcs12 -export -inkey /out/client-other/client.key \
    -in /out/client-other/client.crt -certfile /out/client-other/ca.crt \
    -out /out/client-other/client-other.p12 \
    -passout file:/out/client-other/password.txt

rm -f /out/server/server.csr /out/server/server.ext \
    /out/client/client.key /out/client/client.csr /out/client/client.ext \
    /out/client-other/ca.key /out/client-other/client.key \
    /out/client-other/client.csr /out/client-other/client.ext

# Private material (keys, PKCS12, passwords) stays 600 and the directories 700: only root in the
# container (nginx master) reads them. On a Linux host run: sudo chown -R "$USER" out/mtls
# Public certificates are world-readable.
chmod 700 /out /out/ca /out/server /out/client /out/client-other
chmod 644 /out/ca/ca.crt /out/ca/ca.pem /out/server/server.crt \
    /out/client/client.crt /out/client-other/ca.crt /out/client-other/client.crt
chmod 600 /out/ca/ca.key /out/server/server.key /out/client/client.p12 /out/client/password.txt \
    /out/client-other/client-other.p12 /out/client-other/password.txt
