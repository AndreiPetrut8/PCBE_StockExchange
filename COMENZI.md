# Demo

Buyer:

```
companies
buy AAPL 5 1000
buy TSLA 2 1
orders
modify <id> 1000 3
cancel <id>
portfolio
quit
```

Seller:

```
companies
sell MSFT 3 1
sell AAPL 2 99999
orders
modify <id> 1 2
cancel <id>
portfolio
quit
```

| Comanda | Ce face |
|---|---|
| `companies` | companiile, pretul si actiunile disponibile |
| `buy <simbol> <cantitate> <pret>` | cumpara cand pretul ajunge la sau sub limita |
| `sell <simbol> <cantitate> <pret>` | vinde cand pretul ajunge la sau peste limita |
| `orders` | ordinele deschise, cu id |
| `modify <id> <pret> <cantitate>` | schimba pretul si cantitatea unui ordin deschis |
| `cancel <id>` | anuleaza un ordin deschis |
| `portfolio` | ce ai cumparat sau vandut |
| `quit` | iesire |
