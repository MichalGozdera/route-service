# route-service/libs

JAR-y BRoutera pobierane przez `scripts/setup-brouter-jar.sh` z oficjalnych GitHub releases
(`https://github.com/abrensch/brouter/releases`).

**Nie commituj `*.jar`** — są w `.gitignore`. Pobierany ZIP zawiera m.in. plik
`brouter-<wersja>-all.jar`, skrypt go wyciąga i instaluje do `~/.m2` jako
`local.brouter:brouter:<wersja>`, skąd Maven go zaciąga w route-service/adapter.

Setup raz na nowo sklonowanym repo:

```bash
bash route-service/scripts/setup-brouter-jar.sh
```

Skrypt jest idempotentny — przy ponownym uruchomieniu wykryje JAR w `~/.m2` i nic nie zrobi.
