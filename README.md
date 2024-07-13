# Swedish-BankId-Gateway

## we meed 3 pem contents:
1. truststore: is the Root certificate
2. keystore: is the bankId certificate
3. private-key: is our private key

## Test environment
In **test** environment, files named like FPTestcert5_20240610.p12 are providing `keystore` and `private-key`. `truststore` is a separate file.
Use them commands below to convert the p12 to pem.
#### Note
BankId started to provide FPTestcert5_20240610.pem (in addition to the p12 files). Note that the private key in the FPTestcert5_20240610.pem if encrypted. 
So it's easier to use the p12 files and convert them to pem.


### Converting p12 to pem

```
openssl pkcs12 -in trustStore.p12 -out truststore.pem -nodes
```

For test the truststore password is: coolbeans

```
openssl pkcs12 -in FPTestcert5_20240610.p12 -out decrypted-keystore.pem -nodes
```

For test the keystore password is: qwerty123
