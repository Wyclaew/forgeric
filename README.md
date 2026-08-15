# Forgeric

Forge ve Fabric modlarını **aynı Minecraft kurulumunda** birlikte çalıştırmayı hedefleyen
bir mod yükleyicisi. Forge ve Fabric gibi kurulur; macOS ve Windows'ta çalışır.

> **Durum: alfa.** Mod keşfi, metadata köprüsü, mixin aktarımı ve kurulum çalışıyor.
> Fabric API'ye bağımlı modlar **henüz** çalışmıyor. Ayrıntı: [ARCHITECTURE.md](ARCHITECTURE.md) §8.

---

## Neden şimdi mümkün?

Minecraft 26.2 ile Mojang obfuscation'ı bıraktı. Bu, Forge+Fabric uyumluluğunun tarihsel
olarak en zor kısmını ortadan kaldırdı:

| | 1.21.11 | 26.2 |
|---|---|---|
| `client.jar` | 31 MB + **11.7 MB mapping** | 39 MB, **mapping yok** |
| Sınıf isimleri | `a.b.c()` (karartılmış) | `net/minecraft/client/Camera` |
| Fabric mapping | intermediary gerekli | gerekmiyor (`0.0.0`) |
| Mixin sürümü | NeoForge 0.15.2 ≠ Fabric 0.17.3 | **ikisi de 0.17.3** |

İki taraf artık aynı sınıflara aynı isimle bakıyor ve aynı Mixin sürümünü kullanıyor.
Forgeric bu hizalanmanın üzerine kurulu — bu yüzden 26.2'de çalışır, 1.21.x'te çalışmaz.

Yaklaşım özetle: **NeoForge tabanı alınır, Fabric modları onun üstüne köprülenir.**
NeoForge'un resmî genişleme noktaları (`IModFileReader`, `IModLanguageLoader`) kullanılır;
hiçbir taraf çatallanmaz. Ayrıntılı gerekçe [ARCHITECTURE.md](ARCHITECTURE.md)'de.

---

## Gereksinimler

- **Java 25** — Minecraft 26.2 zaten bunu istiyor
- **NeoForge 26.2.0.59** — Forgeric bunun üstünde çalışır, yerine geçmez
- Test için **Prism Launcher** önerilir

macOS'ta Java kurulumu:

```bash
brew install openjdk@25
```

---

## Derleme

```bash
gradle build
```

Çıktılar:

- `installer/build/libs/forgeric-installer-0.1.0.jar` — dağıtılan tek dosya
- `loader/build/libs/forgeric-loader-0.1.0.jar` — oyunun içinde çalışan köprü
  (installer bunu zaten içinde taşır, ayrıca dağıtmaya gerek yok)

---

## Kurulum

### Grafik arayüz

Installer jar'ına çift tıkla, ya da:

```bash
java -jar forgeric-installer-0.1.0.jar
```

### Komut satırı

Ortamı gör (hangi sürümler var, Prism nerede):

```bash
java -jar forgeric-installer-0.1.0.jar list
```

Yeni bir Prism instance'ı oluştur:

```bash
java -jar forgeric-installer-0.1.0.jar prism --new "Forgeric 26.2"
```

Var olan bir Prism instance'ına kur:

```bash
java -jar forgeric-installer-0.1.0.jar prism --instance "/path/to/instances/MyInstance"
```

Herhangi bir mods klasörüne kur (vanilla launcher, sunucu, başka launcher):

```bash
java -jar forgeric-installer-0.1.0.jar mods ~/.minecraft
```

Yeni instance oluştururken NeoForge'u Prism kendi meta sunucusundan indirir; ayrıca
NeoForge kurmana gerek yok. `mods` hedefinde ise NeoForge'un **önceden kurulu** olması gerekir.

Kurulumdan sonra Forge ve Fabric modlarını aynı `mods` klasörüne at.

---

## Mod çakışmalarını kontrol et (`doctor`)

Bu en önemli komut. Mevcut modların **hiçbiri** Forge ve Fabric bir arada çalışsın diye
tasarlanmadı; bir klasör düzgün görünürken oyun açılışta çökebilir. `doctor` bunu
oyunu başlatmadan söyler:

```bash
java -jar forgeric-installer-0.1.0.jar doctor ~/.minecraft
```

Modları ekledikten **sonra** çalıştır. Tespit ettikleri:

| Bulgu | Neden önemli |
|---|---|
| Aynı mod iki kez kurulu | En sık hata. Bir modun hem Fabric hem NeoForge sürümünü koymak — mixin'ler iki kez uygulanır, içerik iki kez kaydedilir |
| Fabric API gerektiren modlar | Şu an yüklenemezler; crash log yerine burada net liste |
| Çakışan `@Overwrite` | İki mod aynı metodu değiştiriyor — biri sessizce devre dışı kalır |
| Çapraz-loader örtüşmesi | Bir Fabric modu ve bir NeoForge modu aynı sınıfa dokunuyor. Genelde sorunsuz, ama bu kombinasyon hiçbir yerde test edilmedi |
| Paket içi kütüphaneler | Nested jar'lar (JarJar) aynı kütüphanenin iki kopyasını getirebilir |

Her mod için kaç sınıfa dokunduğu da listelenir — bir modun oyunun ne kadarını
yeniden yazdığı, çakışmaya karışma olasılığının en iyi göstergesi.

Kurulum sırasında klasörde zaten mod varsa bu kontrol otomatik çalışır.

**Optimizasyon kuralı:** Bir modun NeoForge sürümü varsa **onu kullan**. Köprü katmanı
üzerinden çalıştırmak ek yük ve ek risk. `doctor` bunu tespit ettiğinde zaten söylüyor.
26.2'de popüler 100 modun 77'si zaten her iki loader'da native mevcut — Forgeric'e
sadece geri kalanı için ihtiyacın var.

---

## Ne çalışıyor, ne çalışmıyor

**Çalışan**

- Fabric jar'larının (`fabric.mod.json`) NeoForge tarafından tanınması ve yüklenmesi
- Fabric mod metadata'sının NeoForge modeline çevrilmesi (id, sürüm, bağımlılıklar, lisans)
- Fabric mixin config'lerinin uygulanması
- Fabric giriş noktalarının (`main`, `client`, `server`) çağrılması
- Forge/NeoForge modları — hiç etkilenmiyor, normal çalışıyor
- Kurulum: Prism instance oluşturma/güncelleme, herhangi bir mods klasörü
- Çakışma analizi (`doctor`) — nested jar'ların içi dahil

**Çalışmayan**

- **Fabric API'ye bağımlı modlar.** Fabric modlarının çoğu `fabric-api` kullanır;
  bunun NeoForge üstünde bir implementasyonu henüz yazılmadı. Yani şu an sadece
  Mixin ve vanilla sınıflarıyla çalışan Fabric modları yüklenir.
- Access Widener, registry köprüsü, ağ protokolü köprüsü
- Kotlin/Scala dil adaptörleri
- 1.21.x ve öncesi (obfuscation nedeniyle — installer bunu reddeder)

---

## Test için hangi modlar?

Şu an Fabric API köprüsü olmadığı için **fabric-api gerektirmeyen** Fabric modları
çalışabilir. 26.2 için Modrinth'ten doğrulanmış adaylar:

| Mod | Ne yapar | Bağımlılık |
|---|---|---|
| `krypton` | Ağ katmanı optimizasyonu | yok |
| `ferrite-core` | Bellek kullanımını düşürür | yok |
| `debugify` | Mojang bug'larını düzeltir | yok |
| `c2me-fabric` | Chunk yükleme paralelleştirme | yok |
| `morechathistory` | Sohbet geçmişi | yok |

Bunların yanına istediğin NeoForge modunu koyabilirsin (JEI, Jade, Xaero's vb.).

**Çalışmayacaklar** (fabric-api gerektiriyor): `sodium`, `iris`, `lithium`,
`entityculling`, `modmenu`, `continuity`, `zoomify`. Bunların çoğunun zaten
NeoForge sürümü var — onu kullan.

---

## Yeni Minecraft sürümü ekleme

Sürüme özgü her şey `profiles/<sürüm>.json` içinde. Java kodu değiştirmeden eklenir:

```bash
python3 tools/new_profile.py 26.3 --write
```

Script; Mojang, NeoForge ve Fabric meta sunucularını sorgular, profili üretir ve
Forgeric'in dayandığı varsayımları kontrol eder — sürüm hâlâ obfuscated mı, iki
tarafın Mixin sürümü hâlâ aynı mı. Uyuşmazlık varsa uyarır.

Sonrasında `gradle/libs.versions.toml` içindeki derleme sürümlerini profile göre
hizala ve yeniden derle.

---

## Proje yapısı

```
profiles/     sürüm profilleri (yeni sürüm = yeni JSON)
loader/       oyunun içinde çalışan köprü
  discovery/    Fabric jar'larını NeoForge'a tanıtır
  language/     Fabric giriş noktalarını çalıştırır
  metadata/     fabric.mod.json -> NeoForge metadata
installer/    kurulum aracı (GUI + CLI)
  scan/         jar analizi + çakışma tespiti
  target/       Prism / mods klasörü hedefleri
tools/        profil üretme scripti
```

---

## Test

```bash
gradle test
```

66 test. İki riskli alan kaplı: metadata çevirisi (sürüm aralıkları, mod id
sanitizasyonu, `fabric.mod.json` okuma) ve çakışma analizi kuralları — ikisi de
sessizce yanlış çalışabilecek türden.

---

## Benzer projeler

**Sinytra Connector** aynı problemi 1.20.1'den beri çözüyor ve çok daha ileride.
Fark: onlar obfuscation'lı sürümler için büyük bir runtime remapping altyapısı kurmak
zorunda kaldı. 26.2'de o katman gereksiz, bu yüzden Forgeric çok daha küçük bir kod
tabanıyla aynı işi yapabiliyor.

## Lisans

LGPL-2.1 (NeoForge ile aynı; SPI'larına karşı derleniyor).
