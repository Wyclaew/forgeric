# Forgeric — Mimari

Bu belge, Forgeric'in **neden** bu şekilde tasarlandığını anlatır. Kod okumadan önce
buradan başla; buradaki kararların çoğu, Minecraft 26.2 ile gelen bir değişikliğin
doğrudan sonucu.

---

## 1. Problem: Forge ve Fabric neden bir arada çalışmaz?

İki mod yükleyicisi de aynı işi yapar ama birbirinden habersiz yapar. Çakıştıkları
noktalar tarihsel olarak şunlardı:

| Çakışma noktası | Forge / NeoForge | Fabric |
|---|---|---|
| Başlangıç sınıfı | `net.neoforged.fml.startup.Client` | `KnotClient` |
| Sınıf yükleyici | FML `classloading` + modül katmanı | `KnotClassLoader` |
| İsimlendirme (mapping) | SRG / Mojmap | intermediary |
| Mod tanımı | `META-INF/neoforge.mods.toml` | `fabric.mod.json` |
| Giriş noktası | `@Mod` anotasyonu | `entrypoints` listesi |
| Bytecode enjeksiyonu | Mixin + AccessTransformer | Mixin + Access Widener |
| Olay sistemi | `IEventBus` | callback kayıtları |

Bunların içinde **en yıkıcı olanı mapping farkıydı**. Minecraft obfuscate edilmiş
(karartılmış) hâlde dağıtıldığı için sınıf ve metot isimleri `a.b.c()` gibiydi.
Her iki ekosistem de bunu okunabilir hâle getirmek için kendi isim setini kullanıyordu:

- Fabric modu şunu çağırır: `class_310.method_1551()`
- Forge modu şunu çağırır: `Minecraft.getInstance()` (SRG üzerinden `func_71410_x`)

Aynı JVM'de ikisi birden çalışamaz, çünkü Minecraft sınıfları bellekte **tek bir**
isim setiyle bulunur. Diğer taraf için çalışma anında bytecode'u yeniden
adlandırmak (runtime remapping) gerekir — ve bu, bu işin tarihsel olarak en zor,
en kırılgan kısmıydı.

---

## 2. 26.2 ile değişen şey: Minecraft artık obfuscate değil

Forgeric'i mümkün kılan bulgu bu. Doğrulaması:

```
1.21.11  →  client.jar (31 MB) + client_mappings.txt (11.7 MB)   ← karartılmış
26.2     →  client.jar (39 MB), mapping dosyası YOK              ← karartılmamış
```

26.2 client.jar'ının içinde 10.952 sınıf var ve **hepsi** okunabilir isimlerde
(`net/minecraft/client/Camera.class`, `net/minecraft/client/main/Main.class`).
Kök dizinde tek bir `a.class` yok. Mojang, sürüm numarası şemasıyla birlikte
(1.21.x → 26.x) obfuscation'ı da bıraktı.

Bunun iki ekosisteme yansıması:

- **Fabric** artık intermediary'ye ihtiyaç duymuyor. `meta.fabricmc.net`, 26.2 için
  intermediary sürümünü `0.0.0` (boş yer tutucu) olarak veriyor ve 26.2 profilinde
  intermediary kütüphanesi hiç listelenmiyor.
- **NeoForge** artık remap adımı çalıştırmıyor. Kurulum profili tek bir işlem
  yapıyor: `PROCESS_MINECRAFT_JAR --apply-patches {BINPATCH}` — yani sadece binary
  yama uyguluyor, isimleri değiştirmiyor.

**Sonuç: Forge modu ile Fabric modu artık aynı sınıfa aynı isimle referans veriyor.**
Bu işin tarihsel olarak en zor %70'i ortadan kalktı.

### Şansa denk gelen ikinci hizalanma

İki taraf, aynı sürüm bytecode kütüphanelerini kullanıyor:

| Kütüphane | NeoForge 26.2.0.59 | Fabric Loader 0.19.3 |
|---|---|---|
| Mixin | `net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7` | **aynı** |
| ASM | `org.ow2.asm:asm:9.10.1` | **aynı** |

NeoForge zaten Fabric'in Mixin çatallamasını kullanıyor. Hatta NeoForge'un kendi
`FMLModContainer` sınıfı `org.spongepowered.asm.mixin.FabricUtil` çağırıyor —
yani Fabric'in mixin config dekoratörleri NeoForge içinde hâlihazırda tanınıyor.
Tek bir Mixin çalışma zamanı her iki tarafa da hizmet edebilir.

---

## 3. Yaklaşım: NeoForge taban, Fabric üstüne köprü

Üç seçenek vardı:

1. **Fabric taban, Forge üstüne** — Reddedildi. NeoForge'un API yüzeyi devasa
   (registry, capability, event bus, network, config, data pack). Bunu Fabric
   üstünde sıfırdan yazmak pratikte imkânsız.
2. **Sıfırdan üçüncü bir yükleyici** — Reddedildi. Her iki ekosistemin de tüm
   iç davranışını taklit etmek gerekir; her güncellemede kırılır.
3. **NeoForge taban, Fabric köprüsü** — Seçildi. ✅

Üçüncüsünün seçilme sebebi teknik: **NeoForge genişletilebilir, Fabric Loader ince.**

NeoForge, `ServiceLoader` üzerinden okunan resmî genişleme noktaları sunuyor
(`net.neoforged.neoforgespi`):

- `IModFileReader` — bir jar'ı okuyup mod dosyasına çevirir
- `IModLanguageLoader` — bir mod için container üretip yaşam döngüsünü yönetir
- `IDependencyLocator` — bağımlılık jar'larını bulur
- `ClassProcessor` — bytecode dönüşümü

Fabric Loader ise `FabricLauncher` diye bir **soyutlama** içeriyor.
`FabricLauncherBase.setLauncher(...)` ile Knot yerine kendi implementasyonunu
koyabiliyorsun. Fabric Loader'ın kendisi Knot'a bağlı değil — Knot sadece varsayılan
implementasyon.

Yani: NeoForge'un içine yasal yollarla giriyoruz, Fabric Loader'ı da kendi
sınıf yükleyicimizin üstünde çalıştırıyoruz. Hiçbir tarafı çatallamıyoruz.

---

## 4. Bileşenler

```
forgeric/
├── profiles/            Sürüm profilleri — her MC sürümü için bir JSON
│   ├── 26.2.json
│   └── schema.json
├── loader/              NeoForge içine giren köprü (oyun çalışırken aktif)
│   └── dev/forgeric/loader/
│       ├── discovery/   fabric.mod.json'lu jar'ları NeoForge'a tanıtır
│       ├── language/    Fabric giriş noktalarını çalıştıran ModContainer
│       └── metadata/    fabric.mod.json → NeoForge metadata dönüşümü
├── installer/           Kurulum aracı (oyundan önce çalışır)
│   └── dev/forgeric/installer/
│       ├── core/        Platform tespiti, gömülü loader jar'ı, log
│       ├── profile/     Sürüm profilini okur/çözer
│       ├── scan/        Jar analizi ve çakışma tespiti (bkz. bölüm 7)
│       ├── target/      Prism / mods klasörü hedefleri
│       └── ui/          Swing arayüz
└── tools/               new_profile.py — yeni sürüm profili üretir
```

Ayrı bir `mixin/` paketi **yok**, çünkü gerekmedi: Fabric'in mixin config'leri
çevrilmiş metadata'nın `[[mixins]]` bölümüne yazılıyor ve NeoForge onları kendi
mixin yükleyicisiyle işliyor (bkz. bölüm 2 — aynı Mixin sürümü).

**Ayrım önemli:** `installer` oyunu kurar ve bir daha çalışmaz. `loader` oyunun
içinde yaşar. İkisi sadece `profiles/*.json` üzerinden konuşur.

---

## 5. Başlangıç akışı

Oyun başladığında sırayla şunlar olur:

```
1. Launcher (Prism)  →  net.neoforged.fml.startup.Client çağrılır
                        (NeoForge'un normal başlangıcı — değiştirilmedi)

2. NeoForge mod keşfi başlar
   └─ ServiceLoader, Forgeric'in FabricModFileReader'ını bulur
      └─ mods/ içindeki her jar için: fabric.mod.json var mı?
         ├─ Evet → IModFile üret, dil yükleyicisi = "fabric" olarak işaretle
         └─ Hayır → null döndür, NeoForge kendi okuyucularıyla devam etsin
                    (Forge modları hiç etkilenmez)

3. Metadata çevirisi (lazy — NeoForge mod bilgisini ilk istediğinde)
   └─ fabric.mod.json → NeoForge'un beklediği config yapısı
      ├─ mod id sanitize edilir (fabric-api-base → fabric_api_base)
      ├─ sürüm aralıkları Maven formatına çevrilir (>=1.2 → [1.2,))
      └─ mixin config'leri [[mixins]] olarak yazılır
            → NeoForge onları kendi mixin yükleyicisiyle uygular;
              ayrı bir mixin köprüsü gerekmez

4. NeoForge mod yükleme döngüsü
   └─ Forgeric'in FabricLanguageLoader'ı her Fabric modu için container üretir
      └─ constructMod() → Fabric "main" ve "client" entrypoint'leri çağrılır
```

**Bu akışta henüz olmayan adım:** Fabric Loader'ın kendi çalışma zamanı
(`FabricLauncher` + `GameProvider` + `FabricLoaderImpl`) bağlanmadı. Yani
`FabricLoader.getInstance()` çağıran bir mod şu an çalışmaz. Mod keşfi ve
entrypoint çağırma bunu gerektirmediği için köprünün geri kalanı bundan
bağımsız çalışıyor; ayrıntı bölüm 8'de.

Forge modları bu akışın hiçbir adımında farklı davranmaz. Forgeric'in tüm
müdahalesi, NeoForge'un "bu jar'ı tanımıyorum" dediği yerde devreye girmekten
ibaret.

---

## 6. Sürüm genişletme modeli

Hedef: yeni bir Minecraft sürümü çıktığında **kod yazmadan** destek eklemek.

Her sürüm için `profiles/<sürüm>.json` var. İçinde o sürüme ait tüm değişkenler:

```json
{
  "minecraft": "26.2",
  "javaMajor": 25,
  "obfuscated": false,
  "neoforge": { "version": "26.2.0.59" },
  "fabric":   { "loader": "0.19.3" },
  "compat":   { "mixin": "0.17.3+mixin.0.8.7", "asm": "9.10.1" }
}
```

`obfuscated` alanı özellikle önemli: 1.21.x ve öncesine geri destek eklenirse
bu `true` olacak ve o zaman remapping katmanı devreye girmesi gerekecek
(bkz. bölüm 8). 26.x için `false` olduğu sürece o katman hiç çalışmaz.

Yeni sürüm eklemek için:

```bash
python3 tools/new_profile.py 26.3
```

Bu betik Mojang, NeoForge ve Fabric meta sunucularını sorgulayıp profili üretir.
Uyumluluk bozulmadıysa kod değişikliği gerekmez.

---

## 7. Çakışma tespiti — neden ayrı bir katman

Forgeric iki ekosistemi bir klasöre koyuyor, ama **hiçbir mod bunun için yazılmadı.**
Bir Fabric modu, yanında bir NeoForge modu olacağını hiç varsaymaz. Sonuç, teknik
olarak "çalışan" ama pratikte bozuk kurulumlar:

- **Aynı mod iki kez.** 26.2'de popüler 100 modun 77'si her iki loader için de
  yayınlanıyor. Kullanıcının ikisini birden koyması an meselesi; mixin'ler iki kez
  uygulanır, içerik iki kez kaydedilir.
- **Mixin savaşları.** İki mod aynı metoda `@Overwrite` uygularsa ikincisi kazanır,
  birincinin değişikliği sessizce yok olur. Crash yok, sadece "şu mod çalışmıyor".
- **Fabric API eksikliği.** Mod yüklenir, sonra `NoClassDefFoundError` ile çöker —
  hata mesajı asıl sebebi göstermez.

Bunların hepsi kurulum anında tespit edilebilir ve crash log'dan okumaktan çok daha
kolay. Bu yüzden `doctor` var.

### Tespit neden statik listeye dayanmıyor

Bilinen çakışan mod çiftlerinin listesini tutmak ilk akla gelen çözüm, ama:
listeyi kim güncelleyecek, ve listede olmayan mod ne olacak? Bunun yerine **her şey
jar'ın kendisinden okunuyor**:

| Bilgi | Kaynak |
|---|---|
| Mod id, sürüm, bağımlılıklar | `fabric.mod.json` / `neoforge.mods.toml` |
| Mixin config listesi | aynı dosyalar |
| Mixin'in dokunduğu gerçek sınıflar | mixin sınıfının bytecode'undaki `@Mixin` annotation'ı (ASM) |
| `@Overwrite` kullanımı | metot annotation'ları |

Bakım gerektirmez ve özel/kapalı modlar dahil her mod için çalışır.

### Nested jar tuzağı

Tespit ilk yazıldığında NeoForge modları için "0 sınıf yamalanıyor" diyordu. Sebep:
**bazı modlar dış jar'ı sadece kabuk olarak kullanıyor.** Sodium'un NeoForge sürümü
mixin config'lerini `mods.toml`'da bildiriyor ama tüm sınıfları ve config dosyalarını
`META-INF/jarjar/` içindeki bir jar'da taşıyor.

Sadece dış jar'a bakmak, düzinelerce sınıfa dokunan bir modu "hiçbir şeye dokunmuyor"
diye raporlamak demekti — ve NeoForge tarafındaki tüm kontroller sessizce devre dışı
kalıyordu. Tarayıcı artık `META-INF/jarjar/` (NeoForge) ve `META-INF/jars/` (Fabric)
altındaki jar'ları da açıyor. Gerçek fark: Sodium-NeoForge 0 → 76 sınıf,
Sodium-Fabric 72 → 207 sınıf.

---

## 8. Bilinen sınırlar ve yol haritası

Dürüst olmak gerekirse, bu iş bittiğinde bile bazı şeyler çalışmayacak.
Neyin nerede durduğu:

### Şu an çalışan (bu sürümde)
- Fabric jar'larının NeoForge tarafından tanınması ve yüklenmesi
- Fabric giriş noktalarının (`main`, `client`) çağrılması
- Fabric mixin config'lerinin uygulanması
- `FabricLoader.getInstance()` API'sinin doğru cevap vermesi
- Kurulum: Prism, MultiMC ve vanilla launcher profilleri

### Henüz çalışmayan
- **Fabric API'ye bağımlı modlar.** Fabric modlarının çoğu `fabric-api`
  kullanır (`fabric-events-*`, `fabric-networking-*`, `fabric-rendering-*`).
  Bu API'nin NeoForge üstünde çalışan bir implementasyonu gerekir — Sinytra
  projesinin "Forgified Fabric API" ile yaptığı iş. Ayrı bir modül olarak
  kademeli yazılacak. **Fabric API kullanmayan modlar şu an çalışmalı.**
- **Access Widener.** Fabric'in erişim genişletme mekanizması, NeoForge'un
  AccessTransformer'ına çevrilmeli.
- **Registry köprüsü.** İki tarafın registry sistemleri farklı; Fabric modunun
  kaydettiği bir blok/eşya NeoForge tarafından görülmeli.
- **Ağ protokolü.** İki tarafın paket sistemleri ayrı.
- **1.21.x ve öncesi.** Obfuscation olduğu için runtime remapping katmanı gerekir.

### Neden bu sıra?
Yukarıdakiler bağımlılık sırasına göre dizili. Fabric API köprüsü olmadan
"gerçek" modların çoğu çalışmaz, ama Fabric API köprüsü de ancak mod yükleme
ve mixin katmanı sağlam olunca yazılabilir. Temelden başlıyoruz.

---

## 9. Bu işi başkaları da yaptı

Dürüstlük gereği: **Sinytra Connector** aynı problemi 1.20.1'den beri çözüyor
ve bizden çok daha ilerideler. Fark şu: onlar obfuscation'lı sürümler için
devasa bir remapping altyapısı kurmak zorunda kaldılar. 26.2'de o katman
gereksiz — bu yüzden Forgeric çok daha küçük bir kod tabanıyla aynı işi
yapabilir. Karşılaştırma ve fikir alınacak yer olarak Connector'a bakmak
mantıklı; kod kopyalanmıyor (onlar LGPL, biz de öyleyiz ama ayrı bir uygulama).
