# HKG Standard Gen5W → CarrotPilot 7706 Bridge (`HKG_5W_CarrotNavi`)

현대·기아 표준형 5세대 와이드(Standard Gen5W, Android 4.4.2 API 19) 순정 헤드유닛(`com.mnsoft.navi` / `com.hkmc.micom.ClusterService`)의 실시간 내비게이션·단속카메라·경로안내(TBT) 데이터를 **CarrotPilot (`UDP 7706`)** 포맷으로 변환·전송하는 NEMESIS 패키지 및 GitHub 자동 업데이트 시스템입니다.

---

## 주요 기능

1. **CarrotPilot `UDP 7706` 실시간 브릿지 (5Hz)**
   - **단속카메라·안전구간 (SDI 7대 항목)**:
     - 고정식 과속카메라 (`nSdiType = 1`), 신호·과속카메라 (`nSdiType = 0`), 이동식 카메라 (`nSdiType = 7`), 후면 번호판인식 카메라 (`nSdiType = 75/76`)
     - 구간단속 카메라 및 구간 내 차량 평균속도 (`nSdiType = 4`, `nSdiBlockType = 2`, `nSdiBlockDist`, `nSdiBlockSpeed`, `nSdiBlockAverageSpeed`)
     - 어린이보호구역 (`nSdiType = 20` / `nSdiPlusType = 20`, 30 km/h 연동)
     - 과속방지턱 (`nSdiType = 22` / `nSdiPlusType = 22`, 도심 일반도로 `roadcate = 4` 보장)
   - **경로안내 (Turn-By-Turn 1차·2차 회전 및 목적지 정보)**:
     - `Navigation$DisplayType`(47종) + 회전 각도(`Direction` `0~360°`) + `SCC_HU_Navi_P`(`nextTurnType1/2`)를 CarrotPilot `nTBTTurnType` / `nTBTTurnTypeNext` 코드로 매핑
     - `com.mnsoft.navi`의 UTF-16LE/BE 바이트 스왑(`CrossroadName`, `DestinationName`) 자동 복원 디코더 탑재 (`szTBTMainText`, `szGoalName`)
     - 목적지 잔여 거리(`nGoPosDist`), 잔여 시간(`nGoPosTime`), 차선 수 기반 도로 폭(`nTBTNextRoadWidth`), GPS(`vpPosPointLat`, `vpPosPointLon`, `nPosAngle`, `nPosSpeed`) 및 목적지 좌표(`goalPosX`, `goalPosY`, `szGoalName`) 전송
   - **Mapbox 경로 폴리라인 연동 (무인증 OSRM → `TCP 7709`, v1.3.1)**:
     - `com.mnsoft.navi.action.DestinationInfo` / `Route_Guide_Status` (스티키 브로드캐스트 + `BrdCast` 로그, `com.mnsoft.navi.action.extra.*` 키 지원)에서 목적지·경유지 좌표(`LON`/`LAT`, `waypoint1/2`, `Passed.waypoint1/2`, `PoiName`/`Address`) 추출 및 `ROUTE_GUIDE_STATE` 활성(`> 0`) 기반 `goalPosX`/`goalPosY` 전송
     - Android `LocationManager` / `ILocationManager` · `IGpsStatusListener`(`onSvStatusChanged` GPS+GLONASS 위성 수·`SNR` / `onNmeaReceived` NMEA) · `dumpsys location`(`satellites`) 기반 실시간 차량 GPS·추측항법(DR)·터널(`STD_SEG_E.tunnel`) 폴링으로 `vpPosPointLat`/`vpPosPointLon` 및 OSRM 출발 좌표 확보
     - Mapbox 토큰 없이 공용 OSRM 서버(`router.project-osrm.org`, 백업 `routing.openstreetmap.de`)로 경로 조회(공용 UDP DNS `8.8.8.8`/`1.1.1.1`) 후 Google Polyline5 디코딩·최대 4096포인트 제한을 거쳐 CarrotPilot `TCP 7709`(`!I` + `lon/lat float32`)로 푸시 (경로 이탈·재탐색 자동 재조회, 안내 종료/전송 OFF 시 초기화, OSRM 폴리라인 기반 2차 회전 방향 추론 및 `dumpsys` 목적지 `0.0 km`/다음 안내 깜빡임 보정 포함)
   - **CarrotPilot 자동 탐색**:
     - `UDP 7705` 브로드캐스트 수신 + `wlan0` ARP 테이블(`/proc/net/arp`) 스캔 + `wlan0` 서브넷 브로드캐스트(`<서브넷>.255:7706`, 실제 `wlan0` IP/프리픽스에서 계산, 기본 `192.168.0.255`) 병행

2. **부팅 시 무선 모드 자동 활성화 (`핫스팟(AP)` / `WiFi(Client)` / `끄기(수동)`)**
   - 대시보드의 `부팅 시 무선 모드` 3단 선택으로 지정하며 `config/hotspot_auto.conf`, `config/wifi_auto.conf`에 저장
   - 표준 Gen5W의 Broadcom 칩은 SoftAP와 STA를 동시에 쓸 수 없으므로(`tcc.wifi.firmware.mode`, `tcc.wifi.concurrent` 미설정) 두 모드는 배타적이며, 한쪽을 고르면 다른 쪽은 자동으로 꺼짐
   - **핫스팟(AP)** (기본값): 부팅 시 `wifi.hotspot.subscription=true`, `wifi.hotspot.apn=Public`, `eth0`(`192.168.0.123`) 서브넷 충돌 제거 후 `service call wifi 29 i32 0 i32 1`로 SoftAP 활성화, 이후 5초 주기로 유지
   - **WiFi(Client)**: 부팅 시 SoftAP를 끄고 `service call wifi 13 i32 1`로 STA 활성화, 부팅·시동 ON 직후 약 60초 동안 STA 상태 재확인 (무선 CarPlay/Android Auto가 같은 `wlan0` AP를 쓰므로 재확인 중에는 켜진 AP를 끄지 않음)
   - **끄기(수동)**: 부팅 시 핫스팟·WiFi를 자동으로 켜지 않음
   - 대시보드 `WiFi(Client) 설정` 버튼으로 순정 WiFi 설정에 들어가면 다음 부팅까지 핫스팟 자동 유지를 일시중지해 수동 WiFi 연결이 끊기지 않도록 함
   - `IWifiManager` 트랜잭션 코드(13/14/29/30)와 상태값은 EV6(`tcc803x`)·니로EV(`tcc897x`) 프레임워크에서 동일함을 확인

3. **GitHub 자동 업데이트 (OTA)**
   - `rmnet1`(블루링크 폐쇄망 DNS `172.19.140.37`) 함정을 우회하기 위해 공용 LTE(`rmnet0`)를 통한 직접 UDP DNS 쿼리(`8.8.8.8` / `1.1.1.1`) 수행
   - Android 4.4.2 `conscrypt.odex`의 `TLSv1.2` + SNI(`setHostname`) 명시적 활성화
   - [`version.json`](version.json) 및 `carrotbridge.jar.sha256` 기반 `SHA-256` 무결성 검증과 데몬 비정상 종료 시 `.bak` 자동 롤백 워치독 내장
   - `/system/bin/hmgapid`가 있는 차량(또는 추후 HMG-API를 설치한 차량)은 다음 내비 ON 확인 시 `carrotbridge-panel.zip`을 자동 다운로드·무결성 검증 후 `hmgapid engine install` + `apply`까지 자동 동기화 (미설치 차량은 완전히 건너뜀)

4. **USB / 로컬 TBT 정밀 덤프 (`scripts/tbt-dump.sh`)**
   - `am broadcast -a com.android.Eng.LogSetting --eia intArray 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0` 브로드캐스트로 `com.mnsoft.navi` 내부 `[Cluster]` 평문 TBT 로거 자동 활성화
   - 경로안내 중 모든 TBT 이벤트와 `dumpsys` 스냅샷을 RAM 디스크(`/dev/hkg_tmp/hkg_tbt_dump/tbt_live_trace.log`, 512 KB 상한 + `.old` 로테이션)에 기록하며, USB 메모리(`/storage/usb0`) 장착 시 `/storage/usb0/HKG_TBT_Dump/`로 자동 복사
   - 대시보드 `TBT 정밀 덤프` 버튼 또는 USB 장착 시 함께 수집되는 파일:
     - 내비·클러스터: `dumpsys_cluster_latest.txt`, `logcat_navi_recent.txt`, `dumpsys_navi_broadcasts.txt`(`DestinationInfo`/`Route_Guide_Status`/`GuidStatus`), `dumpsys_location.txt`
     - 무선 모드 진단: `wireless_state.txt`(무선 모드 설정 파일, STA/AP 상태, 관련 시스템 속성, `hostapd`/`wpa_supplicant`/`dnsmasq` 프로세스), `logcat_wifi_recent.txt`(`WifiService`/`WifiStateMachine`/`WifiSettings`/`CarrotBridge`)
     - 브릿지·네트워크: `carrotbridge_status.json`, `carrotbridge_svc.log`, `carrotbridge_updater.log`, `getprop.txt`, `ip_addr.txt`, `ip_route_all.txt`, `ip_rule.txt`, `arp_table.txt`, `dumpsys_connectivity.txt`, `hmgapi_state.json`, `hmgapi_log_recent.txt`

5. **MAP 버튼 3연타 & NEMESIS 대시보드 상태 오버레이 (`StatusDashboardOverlay`, 대시보드 v2)**
   - 전면 물리 `MAP` 버튼을 연속 3번 누르거나(`ivi_mode_broadcast` `KEYCODE_SHORTCUT_MAP` / `com.hkmc.intent.action.MAP_SHORTCUT` 감지), NEMESIS 대시보드에서 `CarrotNavi 7706 Bridge`를 선택하면 **전체화면(1920×720, 1280×480dp) 상태 대시보드**가 즉시 표시됨 (다시 MAP 3연타 또는 `닫기`로 닫힘, 항상 `상태` 탭으로 열림)
   - **상단 바**: 패키지 버전·업데이트 상태(`v1.3.6 · 최신`), `상태 | 설정` 탭, 상태 아이콘 3개(콤마 기기 IP / 차량 GPS 사용 위성 수 `9기` / 무선 `핫스팟`·`WiFi`, 색상: 초록 정상 · 파랑 추측항법(DR)·무선 · 노랑 확인 필요 · 회색 꺼짐), 종합 상태(`정상` / `확인 필요` / `전송 꺼짐`), 자동 닫힘 카운터(`58초 후 닫힘`, 터치 시 `일시정지`/`재개`, 다시 열면 초기화), 닫기 버튼
   - **상태 탭**
     - **경로 안내**: 회전 아이콘(좌·우·완만한 좌우·U턴·회전교차로·톨게이트·도착), 남은 거리, 회전명, 한글 교차로명, 진행 막대, 다음 안내, 목적지까지 거리·시간 (미안내 시 `경로 안내 없음` 표시)
     - **속도·단속**: 제한속도 표지판, 도로 종별, 현재 차속, 전방 단속카메라/방지턱 거리 또는 구간단속 남은 거리·구간 평균속도
     - **연결**: 콤마 기기(연결 IP·수신 차속·`TCP 7709` 경로 포인트 수), 차량 GPS(`위성 3D · 9기` / `터널 DR · 0기` / `대기 중 · 0/5기`, 사용/관측 위성 수·최대 신호, 위성 사용 비율 막대), 무선(핫스팟/WiFi, `wlan0` IP, 접속 기기 수)
   - **설정 탭**
     - **무선·전송**: `부팅 시 무선 모드` 3단 선택(`핫스팟(AP)` / `WiFi(Client)` / `끄기(수동)`)과 모드별 설명, `콤마 데이터 전송` ON/OFF 스위치
     - **바로가기**: `[핫스팟(AP) 설정]`(`com.hkmc.wifisettings/.WifiHotspot`), `[WiFi(Client) 설정]`(`com.hkmc.wifisettings/.WifiClientSettings`), `[엔지니어링 WiFi]`(`com.hkmc.system.app.engineering/.module.WifiInfo`), `[업데이트]`(GitHub 최신 버전 확인), `[TBT 정밀 덤프]`
     - **점검 정보**: 이 기기 IP, 콤마 기기, 전송 대상, 경로 전송(`TCP 7709`), GPS 상태(사용/관측 위성·`dB-Hz`), GPS 위치·방위, 무선 상태, 브릿지(`UDP 7706` 패킷 번호), CPU 온도·사용률, RAM
   - 아이콘은 이모지 대신 `ShapeDrawable(PathShape)` 선 아이콘으로 그려 Android 4.4(API 19)에서도 동일하게 표시

6. **HMG-API 순정 앱 메뉴 · 사용자 버튼(★) · 분할화면 · WiFi(Client)+LTE 공존 (`package_hmgapi_panel`, v1.2.1)**
   - `Open-HMG/HMG-API`(`>= 1.0.0`)가 설치된 차량에서 순정 **전체 앱 목록**과 **사용자 버튼(★)** 선택지에 **`CarrotNavi`** 를 추가하고, 순정 `설정 → 화면 → 분할 화면` 목록에 **`CarrotNavi`** 페이지를 추가하는 애드온 모드(`carrotbridge-panel.zip`)
   - 연결 카드 3종(콤마 기기 · 차량 GPS 및 위성 사용 막대 · 무선)을 순정 우측 분할화면(`com.mobis.splitscreen.right`)에 실시간 표시하며, 분할화면 카드·앱 아이콘·사용자 버튼(★) 어느 쪽을 눌러도 전체화면 대시보드가 열림
   - **WiFi(Client) 연결 시 LTE 기본 회선 유지 (`keep_lte_on_wifi`, 기본 ON)**: `system_server` `ConnectivityService` 훅(`CarrotWifiLteHook`)으로 부팅 중 `wlan0`이 LTE보다 먼저 붙거나 일시 장애/페일오버가 발생해도 `wlan0`을 보조 링크로만 유지하고 클라이언트 연결 동안 `wlan0` IPv6 RA 경로를 차단해 `rmnet0`(`TYPE_MOBILE`) 기본 회선·DNS를 보호함 (WiFi 해제 시 `wlan0` IPv6 재활성화; 콤마 통신과 순정 `HKMC_MusicStreaming`·블루링크 동시 사용 가능, HMG-API 앱에서 설정 토글 가능)
   - **OTA 자동 설치·업데이트**: `/system/bin/hmgapid`가 감지되면 브릿지 OTA 확인 시 `carrotbridge-panel.zip`을 자동으로 다운로드·설치·적용. HMG-API 미설치 차량은 무시하고, 나중에 HMG-API를 설치하면 그다음 내비 ON 확인 때 자동으로 추가됨. 사용자가 HMG-API 앱에서 모드를 OFF(`.disabled`)해 둔 상태는 그대로 유지하며, 최초 등록 후 순정 `설정 → 화면 → 분할 화면`에서 `CarrotNavi` 체크만 1회 켜면 화면에 표시됨 (훅·앱 아이콘 최초 로드는 1회 재부팅 후 적용)
   - **수동 USB 설치**: 인터넷 없이 설치할 경우 USB 루트 `HMGAPI/updates/` 폴더에 `carrotbridge-panel-1.2.1.zip` 복사 → 헤드유닛 `HMG-API` 앱 **Check USB updates** → 설치 후 **Apply changes** → 재부팅 후 순정 앱 목록·사용자 버튼(★)·분할화면 설정 확인

---

## 빌드 방법

```bash
# CarrotNavi 7706 Bridge 패키지 및 USB 번들(HKG_5W_CarrotNavi_USB.zip) 빌드
python package_carrotnavi_bridge/build_package.py

# HMG-API 순정 앱 메뉴·사용자 버튼(★)·분할화면 모드(carrotbridge-panel-1.2.1.zip) 빌드
python package_hmgapi_panel/build_mod.py

# Engineering WiFi Info 단독 패키지 빌드
python package_engineering_wifiinfo/build_package.py
```

