# Istio Authentication for Event Listener
Istio is configured through the authorization policy and request authentication to look
for JWT:s issued by "https://auth.sreyardship.com/realms/sreyardship". 

We have a long-lived-token-for-apis client configured in our authentication server
we can use to generate long lived JWT tokens (1 year at the time of writing) that 
can be used by gitea webhooks to call out to start pipeline

To generate a token you simply run
```bash
curl --location 'https://auth.sreyardship.com/realms/sreyardship/protocol/openid-connect/token' \
--header 'Content-Type: application/x-www-form-urlencoded' \
--data-urlencode 'client_id=long-lived-tokens-for-apis' \
--data-urlencode 'client_secret=<client-secret>' \
--data-urlencode 'grant_type=client_credentials'
```
The client secret you can find in the authentication server under the correct client.
```



