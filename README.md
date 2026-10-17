# Bursa

Proiect Java organizat pentru varianta distribuita a cerintei: serverul gestioneaza
ordinele si tranzactiile, iar clientii pot actiona ca si cumparatori sau vanzatori.

## Structura

```text
src/
  main/
    java/ro/pcbe/stockexchange/
      Main.java
      client/
        ExchangeClient.java
        buyer/BuyerClient.java
        seller/SellerClient.java
      domain/
        Company.java
        Order.java
        OrderSide.java
        Trade.java
      market/
        MatchingEngine.java
        OrderBook.java
      protocol/
        Message.java
        MessageType.java
      server/
        ClientHandler.java
        ExchangeServer.java
  test/
    java/ro/pcbe/stockexchange/
pom.xml
```

## Responsabilitati

- `domain` contine modelele pentru companii, ordine si tranzactii.
- `market` va contine registrul comun de ordine si logica de potrivire a
  ordinelor de cumparare cu cele de vanzare.
- `server` accepta conexiunile si trateaza fiecare client.
- `client` separa codul comun de comunicare de rolurile cumparator si vanzator.
- `protocol` defineste mesajele schimbate intre clienti si server.
- `Main` este punctul de pornire al aplicatiei.

Structura este scheletul proiectului; logica efectiva de conectare, potrivire si
actualizare a ordinelor urmeaza sa fie implementata in clasele corespunzatoare.

## Compilare

Este necesar JDK 17 sau mai nou si Maven:

```bash
mvn test
```
