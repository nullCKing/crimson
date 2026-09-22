# Discovery report

Produced by `tools/discover`, which runs the shipping `ChannelFilter` and
`XtreamParser` against a live server. No URLs, host names or credentials appear
in this file; channel and category names are provider metadata and are the point
of the report.

Source: **mock server (tools/mock-xtream)**

## Totals

| Measure | Count |
| --- | ---: |
| Channels on the server | 5181 |
| Kept | 432 |
| Dropped | 4749 |
| &nbsp;&nbsp;dropped: country not in the allowlist | 4480 |
| &nbsp;&nbsp;kept: national | 372 |
| &nbsp;&nbsp;dropped: US local, market not allowed | 149 |
| &nbsp;&nbsp;dropped: country could not be determined | 120 |
| &nbsp;&nbsp;kept: allowed local market | 60 |
| Categories | 213 |
| Categories never fetched | 184 |
| Channels avoided by skipping those categories | 4629 |

## Accuracy against generated ground truth

The mock server records the country and market it generated each channel with.
These figures compare the filter's verdict against that.

| Measure | Value |
| --- | ---: |
| Correctly kept | 432 |
| Correctly dropped | 4749 |
| Wrongly kept | 0 |
| Wrongly dropped | 0 |
| Market mis-assigned | 0 |
| Precision | 1.0000 |
| Recall | 1.0000 |

No disagreements.

## Kept channels by country

| Country | Kept |
| --- | ---: |
| US | 273 |
| UK | 77 |
| JP | 42 |
| KR | 40 |

## US markets seen

| Market | Channels |
| --- | ---: |
| Other market | 149 |
| Los Angeles | 15 |
| New York | 15 |
| Miami | 15 |
| Tampa | 15 |

## Sample kept channel names, by country

### US

Original name, then the cleaned display name.

- `US\| Fox Business HD  ->  Fox Business`
- `\|US\| American Heroes  ->  American Heroes`
- `USA: TLC ᴴᴰ  ->  TLC`
- `US - Travel Channel FHD  ->  Travel Channel`
- `US Food Network SD  ->  Food Network`
- `[US] HGTV HEVC  ->  HGTV`
- `USA ▎DIY Network HD  ->  DIY Network`
- `US VIP Cooking Channel FHD  ->  Cooking Channel`
- `US 4K Magnolia Network HEVC  ->  Magnolia Network`
- `USA•AMC  ->  USA•AMC`
- `(USA) IFC 4K  ->  IFC`
- `Sundance TV SD  ->  Sundance TV`
- `USA\| FX SD  ->  FX`
- `\|USA\| FXX  ->  FXX`
- `USA: Paramount Network  ->  Paramount Network`
- `USA - TNT ᴴᴰ  ->  TNT`
- `US TBS  ->  TBS`
- `[USA] USA Network  ->  Network`
- `UNITED STATES ▎Syfy  ->  Syfy`
- `UNITED STATES VIP Bravo  ->  Bravo`

### UK

Original name, then the cleaned display name.

- `ENGLAND ▎BBC One  ->  BBC One`
- `GB VIP BBC Two  ->  BBC Two`
- `UK 4K BBC Three  ->  BBC Three`
- `UK•BBC Four HEVC  ->  UK•BBC Four`
- `(UK) BBC News  ->  BBC News`
- `BBC Parliament 4K  ->  BBC Parliament`
- `UK\| BBC Scotland HEVC  ->  BBC Scotland`
- `\|GB\| BBC Alba ᴴᴰ  ->  BBC Alba`
- `GB: CBBC HEVC  ->  CBBC`
- `UNITED KINGDOM - CBeebies  ->  CBeebies`
- `UK BBC Red Button  ->  BBC Red Button`
- `[GB] ITV 1 [UHD]  ->  ITV 1`
- `ENGLAND ▎ITV 2 ᴴᴰ  ->  ITV 2`
- `UK VIP ITV 3  ->  ITV 3`
- `GBR 4K ITV 4  ->  ITV 4`
- `UK•ITV Be  ->  UK•ITV Be`
- `(UNITED KINGDOM) ITVX ᴴᴰ  ->  ITVX`
- `CITV  ->  CITV`
- `GBR\| Channel 4 [UHD]  ->  Channel 4`
- `\|UNITED KINGDOM\| E4 4K  ->  E4`

### JP

Original name, then the cleaned display name.

- `NHK G FHD  ->  NHK G`
- `JPN\| NHK E SD  ->  NHK E`
- `\|JP\| NHK BS1 ᴴᴰ  ->  NHK BS1`
- `JAPAN: NHK BS Premium  ->  NHK BS`
- `JP - NHK World HD  ->  NHK World`
- `JAPAN Nippon TV ᴴᴰ  ->  Nippon TV`
- `[JAPAN] TV Asahi HD  ->  TV Asahi`
- `JPN ▎TBS HEVC  ->  TBS`
- `JPN VIP TV Tokyo HD  ->  TV Tokyo`
- `JAPAN 4K Fuji TV 4K  ->  Fuji TV`
- `JP•WOWOW Prime 4K  ->  JP•WOWOW Prime`
- `(JPN) WOWOW Live HD  ->  WOWOW Live`
- `WOWOW Cinema  ->  WOWOW Cinema`
- `JP\| Animax  ->  Animax`
- `\|JPN\| AT-X HEVC  ->  AT-X`
- `JAPAN: Kids Station  ->  Kids Station`
- `JP - Cartoon Network Japan SD  ->  Cartoon Network Japan`
- `JP Disney Channel Japan SD  ->  Disney Channel Japan`
- `[JPN] Tokyo MX SD  ->  Tokyo MX`
- `JP ▎BS11 SD  ->  BS11`

### KR

Original name, then the cleaned display name.

- `[KOR] KBS 1 SD  ->  KBS 1`
- `KR ▎KBS 2  ->  KBS 2`
- `SOUTH KOREA VIP KBS World  ->  KBS World`
- `KOREA 4K KBS Drama ᴴᴰ  ->  KBS Drama`
- `KOR•KBS Joy  ->  KOR•KBS Joy`
- `(SOUTH KOREA) KBS N Sports HEVC  ->  KBS N Sports`
- `MBC  ->  MBC`
- `KOREA\| MBC Drama ᴴᴰ  ->  MBC Drama`
- `\|KOREA\| MBC Every1 4K  ->  MBC Every1`
- `KOR: MBC Sports Plus  ->  MBC Sports`
- `KR - MBC Music FHD  ->  MBC Music`
- `KR SBS 4K  ->  SBS`
- `[KR] SBS Plus [UHD]  ->  SBS`
- `KR ▎SBS FunE ᴴᴰ  ->  SBS FunE`
- `KR VIP SBS Sports 4K  ->  SBS Sports`
- `KOREA 4K SBS Golf SD  ->  SBS Golf`
- `KR•SBS Biz HEVC  ->  KR•SBS Biz`
- `(KOREA) JTBC  ->  JTBC`
- `JTBC 2  ->  JTBC 2`
- `KOREA\| JTBC Golf 4K  ->  JTBC Golf`

## Channels classified as US locals

Every channel the local detector fired on, with the market matched or the
reason it was dropped.

| Channel | Category | Verdict | Evidence |
| --- | --- | --- | --- |
| US•KABC ABC 2 Los Angeles [UHD] | US \| LOS ANGELES LOCALS | kept, Los Angeles | country US from "US" (prefix); category names locals; network name in channel; call sign KABC |
| (US) KCBS CBS 3 Los Angeles | US \| LOS ANGELES LOCALS | kept, Los Angeles | country US from "US" (prefix); category names locals; network name in channel; call sign KCBS |
| KNBC NBC 4 Los Angeles HEVC | US \| LOS ANGELES LOCALS | kept, Los Angeles | country US from "US" (prefix); category names locals; network name in channel; call sign KNBC |
| US\| KTTV FOX 5 Los Angeles ᴴᴰ | US \| LOS ANGELES LOCALS | kept, Los Angeles | country US from "US" (prefix); category names locals; network name in channel; call sign KTTV |
| \|US\| KTLA CW 6 Los Angeles SD | US \| LOS ANGELES LOCALS | kept, Los Angeles | country US from "US" (prefix); category names locals; network name in channel; call sign KTLA |
| US: KCAL PBS 7 Los Angeles ᴴᴰ | US \| LOS ANGELES LOCALS | kept, Los Angeles | country US from "US" (prefix); category names locals; network name in channel; call sign KCAL |
| US - KCOP MyNetworkTV 8 Los Angeles | US \| LOS ANGELES LOCALS | kept, Los Angeles | country US from "US" (prefix); category names locals; network name in channel; call sign KCOP |
| US KMEX Telemundo 9 Los Angeles 4K | US \| LOS ANGELES LOCALS | kept, Los Angeles | country US from "US" (prefix); category names locals; network name in channel; call sign KMEX |
| [US] KVEA Univision 10 Los Angeles | US \| LOS ANGELES LOCALS | kept, Los Angeles | country US from "US" (prefix); category names locals; network name in channel; call sign KVEA |
| US ▎KOCE ION 11 Los Angeles SD | US \| LOS ANGELES LOCALS | kept, Los Angeles | country US from "US" (prefix); category names locals; network name in channel; call sign KOCE |
| US VIP WABC ABC 2 New York | US \| NEW YORK LOCALS | kept, New York | country US from "US" (prefix); category names locals; network name in channel; call sign WABC |
| US 4K WCBS CBS 3 New York 4K | US \| NEW YORK LOCALS | kept, New York | country US from "US" (prefix); category names locals; network name in channel; call sign WCBS |
| US•WNBC NBC 4 New York HEVC | US \| NEW YORK LOCALS | kept, New York | country US from "US" (prefix); category names locals; network name in channel; call sign WNBC |
| (US) WNYW FOX 5 New York [UHD] | US \| NEW YORK LOCALS | kept, New York | country US from "US" (prefix); category names locals; network name in channel; call sign WNYW |
| WPIX CW 6 New York SD | US \| NEW YORK LOCALS | kept, New York | country US from "US" (prefix); category names locals; network name in channel; call sign WPIX |
| US\| WWOR PBS 7 New York | US \| NEW YORK LOCALS | kept, New York | country US from "US" (prefix); category names locals; network name in channel; call sign WWOR |
| \|US\| WNET MyNetworkTV 8 New York | US \| NEW YORK LOCALS | kept, New York | country US from "US" (prefix); category names locals; network name in channel; call sign WNET |
| US: WXTV Telemundo 9 New York FHD | US \| NEW YORK LOCALS | kept, New York | country US from "US" (prefix); category names locals; network name in channel; call sign WXTV |
| US - WNJU Univision 10 New York HD | US \| NEW YORK LOCALS | kept, New York | country US from "US" (prefix); category names locals; network name in channel; call sign WNJU |
| US WLNY ION 11 New York 4K | US \| NEW YORK LOCALS | kept, New York | country US from "US" (prefix); category names locals; network name in channel; call sign WLNY |
| [US] WPLG ABC 2 Miami HEVC | US \| MIAMI LOCALS | kept, Miami | country US from "US" (prefix); category names locals; network name in channel; call sign WPLG |
| US ▎WFOR CBS 3 Miami | US \| MIAMI LOCALS | kept, Miami | country US from "US" (prefix); category names locals; network name in channel; call sign WFOR |
| US VIP WTVJ NBC 4 Miami [UHD] | US \| MIAMI LOCALS | kept, Miami | country US from "US" (prefix); category names locals; network name in channel; call sign WTVJ |
| US 4K WSVN FOX 5 Miami SD | US \| MIAMI LOCALS | kept, Miami | country US from "US" (prefix); category names locals; network name in channel; call sign WSVN |
| US•WSFL CW 6 Miami [UHD] | US \| MIAMI LOCALS | kept, Miami | country US from "US" (prefix); category names locals; network name in channel; call sign WSFL |
| (US) WPBT PBS 7 Miami | US \| MIAMI LOCALS | kept, Miami | country US from "US" (prefix); category names locals; network name in channel; call sign WPBT |
| WLTV MyNetworkTV 8 Miami SD | US \| MIAMI LOCALS | kept, Miami | country US from "US" (prefix); category names locals; network name in channel; call sign WLTV |
| US\| WSCV Telemundo 9 Miami 4K | US \| MIAMI LOCALS | kept, Miami | country US from "US" (prefix); category names locals; network name in channel; call sign WSCV |
| \|US\| WAMI Univision 10 Miami HD | US \| MIAMI LOCALS | kept, Miami | country US from "US" (prefix); category names locals; network name in channel; call sign WAMI |
| US: WBFS ION 11 Miami [UHD] | US \| MIAMI LOCALS | kept, Miami | country US from "US" (prefix); category names locals; network name in channel; call sign WBFS |
| US - WFTS ABC 2 Tampa FHD | US \| TAMPA LOCALS | kept, Tampa | country US from "US" (prefix); category names locals; network name in channel; call sign WFTS |
| US WTSP CBS 3 Tampa HEVC | US \| TAMPA LOCALS | kept, Tampa | country US from "US" (prefix); category names locals; network name in channel; call sign WTSP |
| [US] WFLA NBC 4 Tampa HD | US \| TAMPA LOCALS | kept, Tampa | country US from "US" (prefix); category names locals; network name in channel; call sign WFLA |
| US ▎WTVT FOX 5 Tampa ᴴᴰ | US \| TAMPA LOCALS | kept, Tampa | country US from "US" (prefix); category names locals; network name in channel; call sign WTVT |
| US VIP WTOG CW 6 Tampa | US \| TAMPA LOCALS | kept, Tampa | country US from "US" (prefix); category names locals; network name in channel; call sign WTOG |
| US 4K WEDU PBS 7 Tampa ᴴᴰ | US \| TAMPA LOCALS | kept, Tampa | country US from "US" (prefix); category names locals; network name in channel; call sign WEDU |
| US•WMOR MyNetworkTV 8 Tampa 4K | US \| TAMPA LOCALS | kept, Tampa | country US from "US" (prefix); category names locals; network name in channel; call sign WMOR |
| (US) WXPX Telemundo 9 Tampa ᴴᴰ | US \| TAMPA LOCALS | kept, Tampa | country US from "US" (prefix); category names locals; network name in channel; call sign WXPX |
| WVEA Univision 10 Tampa | US \| TAMPA LOCALS | kept, Tampa | country US from "US" (prefix); category names locals; network name in channel; call sign WVEA |
| US\| WTTA ION 11 Tampa HEVC | US \| TAMPA LOCALS | kept, Tampa | country US from "US" (prefix); category names locals; network name in channel; call sign WTTA |
| US\| KABC Los Angeles | US \| LOCAL CHANNELS | kept, Los Angeles | country US from "US" (prefix); category names locals; call sign KABC |
| US\| KCBS Los Angeles | US \| LOCAL CHANNELS | kept, Los Angeles | country US from "US" (prefix); category names locals; call sign KCBS |
| US\| KNBC Los Angeles | US \| LOCAL CHANNELS | kept, Los Angeles | country US from "US" (prefix); category names locals; call sign KNBC |
| US\| KTTV Los Angeles | US \| LOCAL CHANNELS | kept, Los Angeles | country US from "US" (prefix); category names locals; call sign KTTV |
| US\| KTLA Los Angeles | US \| LOCAL CHANNELS | kept, Los Angeles | country US from "US" (prefix); category names locals; call sign KTLA |
| US\| WABC New York | US \| LOCAL CHANNELS | kept, New York | country US from "US" (prefix); category names locals; call sign WABC |
| US\| WCBS New York | US \| LOCAL CHANNELS | kept, New York | country US from "US" (prefix); category names locals; call sign WCBS |
| US\| WNBC New York | US \| LOCAL CHANNELS | kept, New York | country US from "US" (prefix); category names locals; call sign WNBC |
| US\| WNYW New York | US \| LOCAL CHANNELS | kept, New York | country US from "US" (prefix); category names locals; call sign WNYW |
| US\| WPIX New York | US \| LOCAL CHANNELS | kept, New York | country US from "US" (prefix); category names locals; call sign WPIX |
| US\| WPLG Miami | US \| LOCAL CHANNELS | kept, Miami | country US from "US" (prefix); category names locals; call sign WPLG |
| US\| WFOR Miami | US \| LOCAL CHANNELS | kept, Miami | country US from "US" (prefix); category names locals; call sign WFOR |
| US\| WTVJ Miami | US \| LOCAL CHANNELS | kept, Miami | country US from "US" (prefix); category names locals; call sign WTVJ |
| US\| WSVN Miami | US \| LOCAL CHANNELS | kept, Miami | country US from "US" (prefix); category names locals; call sign WSVN |
| US\| WSFL Miami | US \| LOCAL CHANNELS | kept, Miami | country US from "US" (prefix); category names locals; call sign WSFL |
| US\| WFTS Tampa | US \| LOCAL CHANNELS | kept, Tampa | country US from "US" (prefix); category names locals; call sign WFTS |
| US\| WTSP Tampa | US \| LOCAL CHANNELS | kept, Tampa | country US from "US" (prefix); category names locals; call sign WTSP |
| US\| WFLA Tampa | US \| LOCAL CHANNELS | kept, Tampa | country US from "US" (prefix); category names locals; call sign WFLA |
| US\| WTVT Tampa | US \| LOCAL CHANNELS | kept, Tampa | country US from "US" (prefix); category names locals; call sign WTVT |
| US\| WTOG Tampa | US \| LOCAL CHANNELS | kept, Tampa | country US from "US" (prefix); category names locals; call sign WTOG |
| \|US\| WLS ABC 2 Chicago | US \| CHICAGO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WLS (other market) |
| US: WBBM CBS 3 Chicago HEVC | US \| CHICAGO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WBBM (other market) |
| US - WMAQ NBC 4 Chicago [UHD] | US \| CHICAGO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WMAQ (other market) |
| US WFLD FOX 5 Chicago ᴴᴰ | US \| CHICAGO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WFLD (other market) |
| [US] WGN CW 6 Chicago SD | US \| CHICAGO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WGN (other market) |
| US ▎WCIU PBS 7 Chicago | US \| CHICAGO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WCIU (other market) |
| US VIP WTTW MyNetworkTV 8 Chicago | US \| CHICAGO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WTTW (other market) |
| US 4K WPWR Telemundo 9 Chicago | US \| CHICAGO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WPWR (other market) |
| US•WPVI ABC 2 Philadelphia [UHD] | US \| PHILADELPHIA LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WPVI (other market) |
| (US) KYW CBS 3 Philadelphia 4K | US \| PHILADELPHIA LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KYW (other market) |
| WCAU NBC 4 Philadelphia HD | US \| PHILADELPHIA LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WCAU (other market) |
| US\| WTXF FOX 5 Philadelphia [UHD] | US \| PHILADELPHIA LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WTXF (other market) |
| \|US\| WPHL CW 6 Philadelphia SD | US \| PHILADELPHIA LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WPHL (other market) |
| US: WPSG PBS 7 Philadelphia FHD | US \| PHILADELPHIA LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WPSG (other market) |
| US - WHYY MyNetworkTV 8 Philadelphia HEVC | US \| PHILADELPHIA LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WHYY (other market) |
| US WFAA ABC 2 Dallas [UHD] | US \| DALLAS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WFAA (other market) |
| [US] KTVT CBS 3 Dallas HEVC | US \| DALLAS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KTVT (other market) |
| US ▎KXAS NBC 4 Dallas HEVC | US \| DALLAS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KXAS (other market) |
| US VIP KDFW FOX 5 Dallas HEVC | US \| DALLAS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KDFW (other market) |
| US 4K KDFI CW 6 Dallas FHD | US \| DALLAS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KDFI (other market) |
| US•KTXA PBS 7 Dallas | US \| DALLAS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KTXA (other market) |
| (US) KERA MyNetworkTV 8 Dallas | US \| DALLAS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KERA (other market) |
| KTRK ABC 2 Houston 4K | US \| HOUSTON LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KTRK (other market) |
| US\| KHOU CBS 3 Houston SD | US \| HOUSTON LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KHOU (other market) |
| \|US\| KPRC NBC 4 Houston | US \| HOUSTON LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KPRC (other market) |
| US: KRIV FOX 5 Houston ᴴᴰ | US \| HOUSTON LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KRIV (other market) |
| US - KTXH CW 6 Houston SD | US \| HOUSTON LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KTXH (other market) |
| US KIAH PBS 7 Houston SD | US \| HOUSTON LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KIAH (other market) |
| [US] KUHT MyNetworkTV 8 Houston | US \| HOUSTON LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KUHT (other market) |
| US ▎WGCL ABC 2 Atlanta | US \| ATLANTA LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WGCL (other market) |
| US VIP WXIA CBS 3 Atlanta [UHD] | US \| ATLANTA LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WXIA (other market) |
| US 4K WAGA NBC 4 Atlanta HEVC | US \| ATLANTA LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WAGA (other market) |
| US•WPCH FOX 5 Atlanta HEVC | US \| ATLANTA LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WPCH (other market) |
| (US) WATL CW 6 Atlanta | US \| ATLANTA LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WATL (other market) |
| WPBA PBS 7 Atlanta | US \| ATLANTA LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WPBA (other market) |
| US\| WJLA ABC 2 Washington DC SD | US \| WASHINGTON DC LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WJLA (other market) |
| \|US\| WUSA CBS 3 Washington DC [UHD] | US \| WASHINGTON DC LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WUSA (other market) |
| US: WTTG NBC 4 Washington DC | US \| WASHINGTON DC LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WTTG (other market) |
| US - WDCA FOX 5 Washington DC 4K | US \| WASHINGTON DC LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WDCA (other market) |
| US WETA CW 6 Washington DC HEVC | US \| WASHINGTON DC LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WETA (other market) |
| [US] WDCW PBS 7 Washington DC | US \| WASHINGTON DC LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WDCW (other market) |
| US ▎WCVB ABC 2 Boston ᴴᴰ | US \| BOSTON LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WCVB (other market) |
| US VIP WHDH CBS 3 Boston FHD | US \| BOSTON LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WHDH (other market) |
| US 4K WFXT NBC 4 Boston SD | US \| BOSTON LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WFXT (other market) |
| US•WSBK FOX 5 Boston ᴴᴰ | US \| BOSTON LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WSBK (other market) |
| (US) WGBH CW 6 Boston | US \| BOSTON LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WGBH (other market) |
| WLVI PBS 7 Boston 4K | US \| BOSTON LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WLVI (other market) |
| US\| KNXV ABC 2 Phoenix | US \| PHOENIX LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KNXV (other market) |
| \|US\| KPHO CBS 3 Phoenix ᴴᴰ | US \| PHOENIX LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KPHO (other market) |
| US: KPNX NBC 4 Phoenix ᴴᴰ | US \| PHOENIX LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KPNX (other market) |
| US - KSAZ FOX 5 Phoenix | US \| PHOENIX LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KSAZ (other market) |
| US KUTP CW 6 Phoenix [UHD] | US \| PHOENIX LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KUTP (other market) |
| [US] KAET PBS 7 Phoenix HD | US \| PHOENIX LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KAET (other market) |
| US ▎KOMO ABC 2 Seattle 4K | US \| SEATTLE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KOMO (other market) |
| US VIP KIRO CBS 3 Seattle SD | US \| SEATTLE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KIRO (other market) |
| US 4K KCPQ NBC 4 Seattle SD | US \| SEATTLE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KCPQ (other market) |
| US•KZJO FOX 5 Seattle | US \| SEATTLE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KZJO (other market) |
| (US) KSTW CW 6 Seattle 4K | US \| SEATTLE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KSTW (other market) |
| KCTS PBS 7 Seattle | US \| SEATTLE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KCTS (other market) |
| US\| WXYZ ABC 2 Detroit HEVC | US \| DETROIT LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WXYZ (other market) |
| \|US\| WWJ CBS 3 Detroit [UHD] | US \| DETROIT LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WWJ (other market) |
| US: WDIV NBC 4 Detroit HEVC | US \| DETROIT LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WDIV (other market) |
| US - WJBK FOX 5 Detroit | US \| DETROIT LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WJBK (other market) |
| US WKBD CW 6 Detroit | US \| DETROIT LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WKBD (other market) |
| [US] WTVS PBS 7 Detroit 4K | US \| DETROIT LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WTVS (other market) |
| US ▎KSTP ABC 2 Minneapolis | US \| MINNEAPOLIS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KSTP (other market) |
| US VIP WCCO CBS 3 Minneapolis HD | US \| MINNEAPOLIS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WCCO (other market) |
| US 4K KARE NBC 4 Minneapolis ᴴᴰ | US \| MINNEAPOLIS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KARE (other market) |
| US•KMSP FOX 5 Minneapolis [UHD] | US \| MINNEAPOLIS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KMSP (other market) |
| (US) WFTC CW 6 Minneapolis HD | US \| MINNEAPOLIS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WFTC (other market) |
| KTCA PBS 7 Minneapolis SD | US \| MINNEAPOLIS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KTCA (other market) |
| US\| KMGH ABC 2 Denver FHD | US \| DENVER LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KMGH (other market) |
| \|US\| KCNC CBS 3 Denver ᴴᴰ | US \| DENVER LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KCNC (other market) |
| US: KUSA NBC 4 Denver | US \| DENVER LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KUSA (other market) |
| US - KDVR FOX 5 Denver | US \| DENVER LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KDVR (other market) |
| US KWGN CW 6 Denver | US \| DENVER LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KWGN (other market) |
| [US] KRMA PBS 7 Denver SD | US \| DENVER LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KRMA (other market) |
| US ▎WFTV ABC 2 Orlando | US \| ORLANDO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WFTV (other market) |
| US VIP WKMG CBS 3 Orlando 4K | US \| ORLANDO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WKMG (other market) |
| US 4K WESH NBC 4 Orlando ᴴᴰ | US \| ORLANDO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WESH (other market) |
| US•WOFL FOX 5 Orlando | US \| ORLANDO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WOFL (other market) |
| (US) WRBW CW 6 Orlando | US \| ORLANDO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WRBW (other market) |
| WKCF PBS 7 Orlando [UHD] | US \| ORLANDO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WKCF (other market) |
| US\| KXTV ABC 2 Sacramento HD | US \| SACRAMENTO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KXTV (other market) |
| \|US\| KOVR CBS 3 Sacramento | US \| SACRAMENTO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KOVR (other market) |
| US: KCRA NBC 4 Sacramento FHD | US \| SACRAMENTO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KCRA (other market) |
| US - KTXL FOX 5 Sacramento | US \| SACRAMENTO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KTXL (other market) |
| US KMAX CW 6 Sacramento [UHD] | US \| SACRAMENTO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KMAX (other market) |
| [US] KVIE PBS 7 Sacramento [UHD] | US \| SACRAMENTO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KVIE (other market) |
| US ▎KGO ABC 2 San Francisco 4K | US \| SAN FRANCISCO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KGO (other market) |
| US VIP KPIX CBS 3 San Francisco SD | US \| SAN FRANCISCO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KPIX (other market) |
| US 4K KNTV NBC 4 San Francisco ᴴᴰ | US \| SAN FRANCISCO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KNTV (other market) |
| US•KTVU FOX 5 San Francisco | US \| SAN FRANCISCO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KTVU (other market) |
| (US) KICU CW 6 San Francisco FHD | US \| SAN FRANCISCO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KICU (other market) |
| KQED PBS 7 San Francisco FHD | US \| SAN FRANCISCO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KQED (other market) |
| US\| KGTV ABC 2 San Diego SD | US \| SAN DIEGO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KGTV (other market) |
| \|US\| KFMB CBS 3 San Diego | US \| SAN DIEGO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KFMB (other market) |
| US: KNSD NBC 4 San Diego HD | US \| SAN DIEGO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KNSD (other market) |
| US - KSWB FOX 5 San Diego [UHD] | US \| SAN DIEGO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KSWB (other market) |
| US KPBS CW 6 San Diego FHD | US \| SAN DIEGO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KPBS (other market) |
| [US] KUSI PBS 7 San Diego | US \| SAN DIEGO LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KUSI (other market) |
| US ▎KTVI ABC 2 St Louis HEVC | US \| ST LOUIS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KTVI (other market) |
| US VIP KMOV CBS 3 St Louis SD | US \| ST LOUIS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KMOV (other market) |
| US 4K KSDK NBC 4 St Louis HEVC | US \| ST LOUIS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KSDK (other market) |
| US•KDNL FOX 5 St Louis 4K | US \| ST LOUIS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KDNL (other market) |
| (US) KPLR CW 6 St Louis HD | US \| ST LOUIS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KPLR (other market) |
| KETC PBS 7 St Louis HD | US \| ST LOUIS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KETC (other market) |
| US\| WTAE ABC 2 Pittsburgh 4K | US \| PITTSBURGH LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WTAE (other market) |
| \|US\| KDKA CBS 3 Pittsburgh | US \| PITTSBURGH LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KDKA (other market) |
| US: WPXI NBC 4 Pittsburgh SD | US \| PITTSBURGH LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WPXI (other market) |
| US - WPGH FOX 5 Pittsburgh HEVC | US \| PITTSBURGH LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WPGH (other market) |
| US WPCW CW 6 Pittsburgh SD | US \| PITTSBURGH LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WPCW (other market) |
| [US] WQED PBS 7 Pittsburgh SD | US \| PITTSBURGH LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WQED (other market) |
| US ▎WSOC ABC 2 Charlotte SD | US \| CHARLOTTE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WSOC (other market) |
| US VIP WBTV CBS 3 Charlotte HD | US \| CHARLOTTE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WBTV (other market) |
| US 4K WCNC NBC 4 Charlotte HEVC | US \| CHARLOTTE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WCNC (other market) |
| US•WJZY FOX 5 Charlotte | US \| CHARLOTTE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WJZY (other market) |
| (US) WCCB CW 6 Charlotte HD | US \| CHARLOTTE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WCCB (other market) |
| WTVI PBS 7 Charlotte [UHD] | US \| CHARLOTTE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WTVI (other market) |
| US\| WMAR ABC 2 Baltimore FHD | US \| BALTIMORE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WMAR (other market) |
| \|US\| WJZ CBS 3 Baltimore | US \| BALTIMORE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WJZ (other market) |
| US: WBAL NBC 4 Baltimore | US \| BALTIMORE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WBAL (other market) |
| US - WBFF FOX 5 Baltimore 4K | US \| BALTIMORE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WBFF (other market) |
| US WNUV CW 6 Baltimore FHD | US \| BALTIMORE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WNUV (other market) |
| [US] WMPT PBS 7 Baltimore | US \| BALTIMORE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WMPT (other market) |
| US ▎WKRN ABC 2 Nashville HEVC | US \| NASHVILLE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WKRN (other market) |
| US VIP WTVF CBS 3 Nashville SD | US \| NASHVILLE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WTVF (other market) |
| US 4K WSMV NBC 4 Nashville 4K | US \| NASHVILLE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WSMV (other market) |
| US•WZTV FOX 5 Nashville SD | US \| NASHVILLE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WZTV (other market) |
| (US) WUXP CW 6 Nashville HD | US \| NASHVILLE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WUXP (other market) |
| WNPT PBS 7 Nashville ᴴᴰ | US \| NASHVILLE LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WNPT (other market) |
| US\| WEWS ABC 2 Cleveland HEVC | US \| CLEVELAND LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WEWS (other market) |
| \|US\| WOIO CBS 3 Cleveland [UHD] | US \| CLEVELAND LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WOIO (other market) |
| US: WKYC NBC 4 Cleveland HD | US \| CLEVELAND LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WKYC (other market) |
| US - WJW FOX 5 Cleveland | US \| CLEVELAND LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WJW (other market) |
| US WUAB CW 6 Cleveland ᴴᴰ | US \| CLEVELAND LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WUAB (other market) |
| [US] WVIZ PBS 7 Cleveland HD | US \| CLEVELAND LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign WVIZ (other market) |
| US ▎KATU ABC 2 Portland | US \| PORTLAND LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KATU (other market) |
| US VIP KOIN CBS 3 Portland HEVC | US \| PORTLAND LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KOIN (other market) |
| US 4K KGW NBC 4 Portland FHD | US \| PORTLAND LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KGW (other market) |
| US•KPTV FOX 5 Portland FHD | US \| PORTLAND LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KPTV (other market) |
| (US) KRCW CW 6 Portland | US \| PORTLAND LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KRCW (other market) |
| KOPB PBS 7 Portland | US \| PORTLAND LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KOPB (other market) |
| US\| KTNV ABC 2 Las Vegas | US \| LAS VEGAS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KTNV (other market) |
| \|US\| KLAS CBS 3 Las Vegas | US \| LAS VEGAS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KLAS (other market) |
| US: KSNV NBC 4 Las Vegas HEVC | US \| LAS VEGAS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KSNV (other market) |
| US - KVVU FOX 5 Las Vegas 4K | US \| LAS VEGAS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KVVU (other market) |
| US KVCW CW 6 Las Vegas [UHD] | US \| LAS VEGAS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KVCW (other market) |
| [US] KLVX PBS 7 Las Vegas 4K | US \| LAS VEGAS LOCALS | dropped, Other market not allowed | country US from "US" (prefix); category names locals; network name in channel; call sign KLVX (other market) |

## Unrecognised prefixes

Leading tokens on channels dropped because no country could be determined.
A token appearing often here is a naming convention the country lists in
`CountryDetector` or `ForeignCountries` should learn.

| Prefix token | Channels | Already a known foreign marker |
| --- | ---: | --- |
| `ACTION` | 9 | no |
| `PLUS` | 9 | no |
| `MUSIC` | 8 | no |
| `COMEDY` | 8 | no |
| `TRAVEL` | 7 | no |
| `ENTERTAINMENT` | 7 | no |
| `LIVE` | 7 | no |
| `MAX` | 6 | no |
| `EXTRA` | 6 | no |
| `LIFESTYLE` | 6 | no |
| `PREMIUM` | 6 | no |
| `CINEMA` | 6 | no |
| `SPORTS` | 6 | no |
| `DRAMA` | 5 | no |
| `KIDS` | 5 | no |
| `FAMILY` | 4 | no |
| `HISTORY` | 4 | no |
| `GOLD` | 3 | no |
| `NATURE` | 3 | no |
| `NEWS` | 2 | no |
| `CLASSIC` | 2 | no |
| `DOCUMENTARY` | 1 | no |

## Every category and its classification

| Category | Country | Locals | Fetched during import |
| --- | --- | --- | --- |
| USA \| ENTERTAINMENT | US | - | yes |
| USA \| SPORTS | US | - | yes |
| USA \| NEWS | US | - | yes |
| USA \| MOVIES | US | - | yes |
| US \| KIDS | US | - | yes |
| US \| DOCUMENTARY | US | - | yes |
| US \| 24/7 CHANNELS | US | - | yes |
| 24/7 SHOWS & MOVIES | unknown | - | yes |
| US \| LOS ANGELES LOCALS | US | locals, Los Angeles | yes |
| US \| NEW YORK LOCALS | US | locals, New York | yes |
| US \| MIAMI LOCALS | US | locals, Miami | yes |
| US \| TAMPA LOCALS | US | locals, Tampa | yes |
| US \| LOCAL CHANNELS | US | locals, market unknown | yes |
| US \| CHICAGO LOCALS | US | locals, Other market | **no** |
| US \| PHILADELPHIA LOCALS | US | locals, Other market | **no** |
| US \| DALLAS LOCALS | US | locals, Other market | **no** |
| US \| HOUSTON LOCALS | US | locals, Other market | **no** |
| US \| ATLANTA LOCALS | US | locals, Other market | **no** |
| US \| WASHINGTON DC LOCALS | US | locals, Other market | **no** |
| US \| BOSTON LOCALS | US | locals, Other market | **no** |
| US \| PHOENIX LOCALS | US | locals, Other market | **no** |
| US \| SEATTLE LOCALS | US | locals, Other market | **no** |
| US \| DETROIT LOCALS | US | locals, Other market | **no** |
| US \| MINNEAPOLIS LOCALS | US | locals, Other market | **no** |
| US \| DENVER LOCALS | US | locals, Other market | **no** |
| US \| ORLANDO LOCALS | US | locals, Other market | **no** |
| US \| SACRAMENTO LOCALS | US | locals, Other market | **no** |
| US \| SAN FRANCISCO LOCALS | US | locals, Other market | **no** |
| US \| SAN DIEGO LOCALS | US | locals, Other market | **no** |
| US \| ST LOUIS LOCALS | US | locals, Other market | **no** |
| US \| PITTSBURGH LOCALS | US | locals, Other market | **no** |
| US \| CHARLOTTE LOCALS | US | locals, Other market | **no** |
| US \| BALTIMORE LOCALS | US | locals, Other market | **no** |
| US \| NASHVILLE LOCALS | US | locals, Other market | **no** |
| US \| CLEVELAND LOCALS | US | locals, Other market | **no** |
| US \| PORTLAND LOCALS | US | locals, Other market | **no** |
| US \| LAS VEGAS LOCALS | US | locals, Other market | **no** |
| UK \| ENTERTAINMENT | UK | - | yes |
| GBR \| SPORTS | UK | - | yes |
| UNITED KINGDOM \| NEWS | UK | - | yes |
| UK \| MOVIES | UK | - | yes |
| GB \| KIDS | UK | - | yes |
| JP \| ENTERTAINMENT | JP | - | yes |
| JP \| SPORTS | JP | - | yes |
| JAPAN \| NEWS | JP | - | yes |
| JP \| MOVIES | JP | - | yes |
| JP \| KIDS | JP | - | yes |
| SOUTH KOREA \| ENTERTAINMENT | KR | - | yes |
| SOUTH KOREA \| SPORTS | KR | - | yes |
| KR \| NEWS | KR | - | yes |
| SOUTH KOREA \| MOVIES | KR | - | yes |
| GERMANY \| ENTERTAINMENT | other (GERMANY) | - | **no** |
| GER \| SPORTS | other (GER) | - | **no** |
| GER \| NEWS | other (GER) | - | **no** |
| GERMANY \| MOVIES | other (GERMANY) | - | **no** |
| FR \| ENTERTAINMENT | other (FR) | - | **no** |
| FRANCE \| SPORTS | other (FRANCE) | - | **no** |
| FRA \| NEWS | other (FRA) | - | **no** |
| FRA \| MOVIES | other (FRA) | - | **no** |
| ES \| ENTERTAINMENT | other (ES) | - | **no** |
| SPAIN \| SPORTS | other (SPAIN) | - | **no** |
| SPAIN \| NEWS | other (SPAIN) | - | **no** |
| ESP \| MOVIES | other (ESP) | - | **no** |
| ITALY \| ENTERTAINMENT | other (ITALY) | - | **no** |
| ITA \| SPORTS | other (ITA) | - | **no** |
| ITALY \| NEWS | other (ITALY) | - | **no** |
| ITALY \| MOVIES | other (ITALY) | - | **no** |
| NL \| ENTERTAINMENT | other (NL) | - | **no** |
| NLD \| SPORTS | other (NLD) | - | **no** |
| NL \| NEWS | other (NL) | - | **no** |
| NLD \| MOVIES | other (NLD) | - | **no** |
| PT \| ENTERTAINMENT | other (PT) | - | **no** |
| POR \| SPORTS | other (POR) | - | **no** |
| POR \| NEWS | other (POR) | - | **no** |
| PORTUGAL \| MOVIES | other (PORTUGAL) | - | **no** |
| PL \| ENTERTAINMENT | other (PL) | - | **no** |
| PL \| SPORTS | other (PL) | - | **no** |
| PL \| NEWS | other (PL) | - | **no** |
| POL \| MOVIES | other (POL) | - | **no** |
| RO \| ENTERTAINMENT | other (RO) | - | **no** |
| RO \| SPORTS | other (RO) | - | **no** |
| RO \| NEWS | other (RO) | - | **no** |
| RO \| MOVIES | other (RO) | - | **no** |
| RUS \| ENTERTAINMENT | other (RUS) | - | **no** |
| RU \| SPORTS | other (RU) | - | **no** |
| RU \| NEWS | other (RU) | - | **no** |
| RU \| MOVIES | other (RU) | - | **no** |
| TUR \| ENTERTAINMENT | other (TUR) | - | **no** |
| TURKEY \| SPORTS | other (TURKEY) | - | **no** |
| TR \| NEWS | other (TR) | - | **no** |
| TUR \| MOVIES | other (TUR) | - | **no** |
| GREECE \| ENTERTAINMENT | other (GREECE) | - | **no** |
| GR \| SPORTS | other (GR) | - | **no** |
| GRC \| NEWS | other (GRC) | - | **no** |
| GR \| MOVIES | other (GR) | - | **no** |
| SWE \| ENTERTAINMENT | other (SWE) | - | **no** |
| SE \| SPORTS | other (SE) | - | **no** |
| SWEDEN \| NEWS | other (SWEDEN) | - | **no** |
| SWE \| MOVIES | other (SWE) | - | **no** |
| NO \| ENTERTAINMENT | other (NO) | - | **no** |
| NORWAY \| SPORTS | other (NORWAY) | - | **no** |
| NO \| NEWS | other (NO) | - | **no** |
| NO \| MOVIES | other (NO) | - | **no** |
| DK \| ENTERTAINMENT | other (DK) | - | **no** |
| DK \| SPORTS | other (DK) | - | **no** |
| DK \| NEWS | other (DK) | - | **no** |
| DK \| MOVIES | other (DK) | - | **no** |
| FI \| ENTERTAINMENT | other (FI) | - | **no** |
| FI \| SPORTS | other (FI) | - | **no** |
| FIN \| NEWS | other (FIN) | - | **no** |
| FI \| MOVIES | other (FI) | - | **no** |
| CANADA \| ENTERTAINMENT | other (CANADA) | - | **no** |
| CANADA \| SPORTS | other (CANADA) | - | **no** |
| CA \| NEWS | other (CA) | - | **no** |
| CAN \| MOVIES | other (CAN) | - | **no** |
| MX \| ENTERTAINMENT | other (MX) | - | **no** |
| MX \| SPORTS | other (MX) | - | **no** |
| MX \| NEWS | other (MX) | - | **no** |
| MEX \| MOVIES | other (MEX) | - | **no** |
| BRA \| ENTERTAINMENT | other (BRA) | - | **no** |
| BR \| SPORTS | other (BR) | - | **no** |
| BRA \| NEWS | other (BRA) | - | **no** |
| BRAZIL \| MOVIES | other (BRAZIL) | - | **no** |
| ARG \| ENTERTAINMENT | other (ARG) | - | **no** |
| AR \| SPORTS | other (AR) | - | **no** |
| AR \| NEWS | other (AR) | - | **no** |
| ARGENTINA \| MOVIES | other (ARGENTINA) | - | **no** |
| CHL \| ENTERTAINMENT | other (CHL) | - | **no** |
| CL \| SPORTS | other (CL) | - | **no** |
| CHILE \| NEWS | other (CHILE) | - | **no** |
| CHILE \| MOVIES | other (CHILE) | - | **no** |
| COL \| ENTERTAINMENT | other (COL) | - | **no** |
| CO \| SPORTS | other (CO) | - | **no** |
| COLOMBIA \| NEWS | other (COLOMBIA) | - | **no** |
| COLOMBIA \| MOVIES | other (COLOMBIA) | - | **no** |
| IN \| ENTERTAINMENT | other (IN) | - | **no** |
| INDIA \| SPORTS | other (INDIA) | - | **no** |
| IN \| NEWS | other (IN) | - | **no** |
| IND \| MOVIES | other (IND) | - | **no** |
| PAKISTAN \| ENTERTAINMENT | other (PAKISTAN) | - | **no** |
| PK \| SPORTS | other (PK) | - | **no** |
| PAKISTAN \| NEWS | other (PAKISTAN) | - | **no** |
| PAK \| MOVIES | other (PAK) | - | **no** |
| CN \| ENTERTAINMENT | other (CN) | - | **no** |
| CHINA \| SPORTS | other (CHINA) | - | **no** |
| CHINA \| NEWS | other (CHINA) | - | **no** |
| CHINA \| MOVIES | other (CHINA) | - | **no** |
| TAIWAN \| ENTERTAINMENT | other (TAIWAN) | - | **no** |
| TAIWAN \| SPORTS | other (TAIWAN) | - | **no** |
| TW \| NEWS | other (TW) | - | **no** |
| TW \| MOVIES | other (TW) | - | **no** |
| TH \| ENTERTAINMENT | other (TH) | - | **no** |
| TH \| SPORTS | other (TH) | - | **no** |
| THAILAND \| NEWS | other (THAILAND) | - | **no** |
| THAILAND \| MOVIES | other (THAILAND) | - | **no** |
| VIETNAM \| ENTERTAINMENT | other (VIETNAM) | - | **no** |
| VIETNAM \| SPORTS | other (VIETNAM) | - | **no** |
| VN \| NEWS | other (VN) | - | **no** |
| VIETNAM \| MOVIES | other (VIETNAM) | - | **no** |
| PH \| ENTERTAINMENT | other (PH) | - | **no** |
| PHL \| SPORTS | other (PHL) | - | **no** |
| PHL \| NEWS | other (PHL) | - | **no** |
| PHILIPPINES \| MOVIES | other (PHILIPPINES) | - | **no** |
| IDN \| ENTERTAINMENT | other (IDN) | - | **no** |
| INDONESIA \| SPORTS | other (INDONESIA) | - | **no** |
| IDN \| NEWS | other (IDN) | - | **no** |
| IDN \| MOVIES | other (IDN) | - | **no** |
| MY \| ENTERTAINMENT | other (MY) | - | **no** |
| MYS \| SPORTS | other (MYS) | - | **no** |
| MALAYSIA \| NEWS | other (MALAYSIA) | - | **no** |
| MYS \| MOVIES | other (MYS) | - | **no** |
| AUS \| ENTERTAINMENT | other (AUS) | - | **no** |
| AU \| SPORTS | other (AU) | - | **no** |
| AUS \| NEWS | other (AUS) | - | **no** |
| AUS \| MOVIES | other (AUS) | - | **no** |
| NZ \| ENTERTAINMENT | other (NZ) | - | **no** |
| NZ \| SPORTS | other (NZ) | - | **no** |
| NEW ZEALAND \| NEWS | other (NEW ZEALAND) | - | **no** |
| NZ \| MOVIES | other (NZ) | - | **no** |
| SOUTH AFRICA \| ENTERTAINMENT | other (SOUTH AFRICA) | - | **no** |
| ZA \| SPORTS | other (ZA) | - | **no** |
| ZA \| NEWS | other (ZA) | - | **no** |
| ZAF \| MOVIES | other (ZAF) | - | **no** |
| EGYPT \| ENTERTAINMENT | other (EGYPT) | - | **no** |
| EGYPT \| SPORTS | other (EGYPT) | - | **no** |
| EGYPT \| NEWS | other (EGYPT) | - | **no** |
| EGYPT \| MOVIES | other (EGYPT) | - | **no** |
| SAU \| ENTERTAINMENT | other (SAU) | - | **no** |
| SA \| SPORTS | other (SA) | - | **no** |
| SAUDI ARABIA \| NEWS | other (SAUDI ARABIA) | - | **no** |
| SAU \| MOVIES | other (SAU) | - | **no** |
| EMIRATES \| ENTERTAINMENT | other (EMIRATES) | - | **no** |
| AE \| SPORTS | other (AE) | - | **no** |
| AE \| NEWS | other (AE) | - | **no** |
| EMIRATES \| MOVIES | other (EMIRATES) | - | **no** |
| ISR \| ENTERTAINMENT | other (ISR) | - | **no** |
| ISRAEL \| SPORTS | other (ISRAEL) | - | **no** |
| IL \| NEWS | other (IL) | - | **no** |
| ISRAEL \| MOVIES | other (ISRAEL) | - | **no** |
| NG \| ENTERTAINMENT | other (NG) | - | **no** |
| NIGERIA \| SPORTS | other (NIGERIA) | - | **no** |
| NGA \| NEWS | other (NGA) | - | **no** |
| NGA \| MOVIES | other (NGA) | - | **no** |
| KE \| ENTERTAINMENT | other (KE) | - | **no** |
| KEN \| SPORTS | other (KEN) | - | **no** |
| KE \| NEWS | other (KE) | - | **no** |
| KENYA \| MOVIES | other (KENYA) | - | **no** |
| SERBIA \| ENTERTAINMENT | other (SERBIA) | - | **no** |
| SRB \| SPORTS | other (SRB) | - | **no** |
| SERBIA \| NEWS | other (SERBIA) | - | **no** |
| SERBIA \| MOVIES | other (SERBIA) | - | **no** |
| GENERAL ENTERTAINMENT | unknown | - | yes |
| IN THE MIX | unknown | - | yes |

