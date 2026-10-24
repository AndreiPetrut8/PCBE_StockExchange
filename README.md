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
- `market` pastreaza ordinele pe companie si le executa cand pretul simulat
  atinge limita ordinului.
- `server` accepta conexiunile si trateaza fiecare client.
- `client` separa codul comun de comunicare de rolurile cumparator si vanzator.
- `protocol` defineste mesajele schimbate intre clienti si server.
- `Main` este punctul de pornire al aplicatiei.

Ordinele BUY se executa cand pretul companiei ajunge la sau sub pretul limita;
ordinele SELL se executa cand pretul ajunge la sau peste limita. Fiecare ordin
declansat se executa integral. Un BUY ramane deschis daca firma nu are suficiente
actiuni disponibile. Simularea actualizeaza preturile prin `MatchingEngine`;
tranzactiile actualizeaza cantitatea disponibila a companiei, nu pretul simulat.

## Compilare

Este necesar JDK 17 sau mai nou si Maven:

```bash
mvn test
```

## Rulare

```bash
mvn -q compile
java -cp target/classes ro.pcbe.stockexchange.Main
java -cp target/classes ro.pcbe.stockexchange.client.buyer.BuyerClient ana
java -cp target/classes ro.pcbe.stockexchange.client.seller.SellerClient dan
```
