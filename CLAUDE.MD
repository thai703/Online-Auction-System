## 1. EXECUTIVE SUMMARY

**Chấm thẳng:** project này **chưa nên deploy ngay** lên VPS Linux/Docker.

| Hạng mục | Điểm |
|---|---:|
| Sẵn sàng production | **2/10** |
| An toàn bảo mật hiện tại | **2/10** |
| An toàn khi 100–150 người cùng test/spam | **2/10** |
| Rủi ro bị lớp vào “kiếm điểm” | **9/10** |

### 5–10 lỗi nghiêm trọng nhất cần sửa ngay

1. **Có thể tự đăng ký tài khoản ADMIN** bằng cách sửa request `POST /register`.
2. **Có thể sửa profile/password của user khác** qua hidden field ở `/profile`.
3. **Hard-coded secret + default admin + bootstrap admin** trong `application.properties`, có thể dẫn tới **đăng nhập admin hoặc forge JWT** nếu lộ source/config.
4. **Top-up ví là giả lập hoàn toàn**: user tự nạp bao nhiêu cũng được, không có gateway xác thực.
5. **Race condition ở place bid**: current price/winner có thể sai khi nhiều request song song.
6. **Race condition ở payment/transaction**: có thể tạo transaction trùng, double-pay, ledger/balance inconsistent.
7. **Mã verify 5 chữ số + không hết hạn + không rate-limit**, rất dễ brute-force/spam.
8. **Private auction không thật sự private**: metadata vẫn lộ, code unlock brute-force được, WebSocket topic dễ bị nghe lén.
9. **Stored DOM XSS** qua WebSocket do dùng `innerHTML`/`insertAdjacentHTML` với dữ liệu user-controlled.
10. **Deploy chưa sẵn sàng**: không thấy Dockerfile/compose/nginx, path upload kiểu Windows, URL localhost hard-code, WebSocket origin sai, `ddl-auto=update`, Flyway tắt.

### Kết luận ngắn

- Đây hiện tại là một **demo app** hơn là một app production-ready.
- Điểm nguy hiểm nhất không phải SQL injection, mà là **mass assignment / IDOR / hardcoded secrets / race condition / fake wallet / XSS**.
- Tôi **không thấy SQL injection trực tiếp rõ ràng** trong repository/query hiện tại; phần query đa số dùng bind params. Nhưng điều đó **không cứu được hệ thống** vì các lỗ hổng khác đã quá nặng.

---

## 2. DANH SÁCH LỖI / RỦI RO CHI TIẾT

### 2.1. Client có thể tự chọn role=ADMIN khi đăng ký

- **Tiêu đề lỗi:** Mass assignment ở đăng ký dẫn đến leo quyền ADMIN
- **Mức độ:** **Critical**
- **Nhóm lỗi:** Security / Authorization
- **Vị trí:**  
  - `src/main/java/com/example/auctions/controller/UserController.java` → `registerUser()`  
  - `src/main/java/com/example/auctions/service/UserService.java` → `registerUser()`  
  - `src/main/resources/templates/register.html`
- **Mô tả vấn đề:**  
  Form UI chỉ hiện BUYER/SELLER, nhưng backend bind trực tiếp `@ModelAttribute("user") User user` rồi save luôn `user.getRole()`. Không có whitelist server-side. `Google OAuth onboarding` có chặn ADMIN, nhưng luồng đăng ký thường thì **không chặn**.
- **Kịch bản khai thác thực tế:**  
  Dùng DevTools/Burp sửa body `POST /register` thành `role=ADMIN`. Nếu email verification bật, attacker chỉ cần verify account của chính họ là có admin.
- **Hậu quả:**  
  Full admin takeover toàn hệ thống: xem/sửa/xóa dữ liệu, khóa user, duyệt/huỷ auction.
- **Cách sửa cụ thể:**  
  - Không bind trực tiếp entity `User` cho form đăng ký.  
  - Tạo `RegisterRequest DTO`.  
  - Ở service/controller chỉ cho phép `BUYER` hoặc `SELLER`.  
  - Tốt hơn nữa: server tự map role từ enum allowlist, không trust client.
- **Ưu tiên sửa:** **ngay**

---

### 2.2. Có thể sửa profile của user khác và đổi luôn mật khẩu của họ

- **Tiêu đề lỗi:** IDOR/BOLA ở `/profile` + password overwrite qua hidden field
- **Mức độ:** **Critical**
- **Nhóm lỗi:** Authorization / Security / Data integrity
- **Vị trí:**  
  - `src/main/resources/templates/profile.html` (hidden `id`, `email`, `password`, `role`)  
  - `src/main/java/com/example/auctions/controller/UserController.java` → `updateProfile()`  
  - `src/main/java/com/example/auctions/service/UserService.java` → `updateUser()`
- **Mô tả vấn đề:**  
  Trang profile render ra hidden field gồm cả `id`, `email`, `password`, `role`. Backend nhận lại nguyên `User user`, rồi `updateUser()` load theo `user.getId()`. Nếu password gửi lên khác với password hiện tại trong DB thì nó encode và save luôn.
- **Kịch bản khai thác hoặc cách lỗi xảy ra:**  
  Một user thường sửa hidden `id` thành ID của nạn nhân, sửa `password` thành mật khẩu mới, submit form. Hệ thống sẽ đổi `fullName/phone/password` của nạn nhân. Đây là account takeover thực tế.
- **Hậu quả:**  
  Cướp tài khoản buyer/seller/admin; phá dữ liệu profile; reset password trái phép.
- **Cách sửa cụ thể:**  
  - **Bỏ toàn bộ hidden field `id/email/password/role` khỏi form profile**.  
  - Dùng `ProfileUpdateRequest DTO`.  
  - Ở service, lấy user ID từ `AuthenticationPrincipal`, **không lấy từ request**.  
  - Đổi mật khẩu phải qua endpoint riêng, bắt buộc nhập `currentPassword`.
- **Ưu tiên sửa:** **ngay**

---

### 2.3. Hard-coded secret/default credential, có thể dẫn tới login admin hoặc forge JWT

- **Tiêu đề lỗi:** Secrets và default credential hard-code trong repo/config
- **Mức độ:** **Critical**
- **Nhóm lỗi:** Security / Deployment
- **Vị trí:**  
  - `src/main/resources/application.properties`  
  - `src/main/java/com/example/auctions/config/AdminBootstrapConfig.java`  
  - `src/main/java/com/example/auctions/security/JwtTokenProvider.java`  
  - `src/main/java/com/example/auctions/security/JwtAuthenticationFilter.java`
- **Mô tả vấn đề:**  
  Trong `application.properties` đang hard-code:
  - DB user `root`, password rỗng
  - Gmail account + app password
  - Google OAuth client secret
  - JWT secret
  - default admin email/password
  - bootstrap admin bật
- **Kịch bản khai thác hoặc cách lỗi xảy ra:**  
  - Nếu source/config bị lộ, attacker có thể thử login admin bằng default creds.  
  - Nặng hơn: vì JWT secret đã biết và JWT filter chỉ cần subject hợp lệ, attacker có thể **forge JWT cho `admin@auctions.local`**. Filter sẽ load admin user từ DB và authenticate luôn.
- **Hậu quả:**  
  Compromise toàn hệ thống, mail account leak, OAuth client leak, DB leak, giả mạo token.
- **Cách sửa cụ thể:**  
  - Đưa toàn bộ secret sang env var / Docker secret.  
  - Xóa khỏi git.  
  - Rotate tất cả secret đã lộ.  
  - Tắt bootstrap admin sau khi seed lần đầu.  
  - Không dùng DB root, không dùng password rỗng.
- **Ưu tiên sửa:** **ngay**

---

### 2.4. Ví có thể nạp tiền giả không giới hạn

- **Tiêu đề lỗi:** Top-up wallet không xác thực, ai cũng tự cộng tiền được
- **Mức độ:** **Critical**
- **Nhóm lỗi:** Security / Data integrity / Business logic
- **Vị trí:**  
  - `src/main/java/com/example/auctions/controller/WalletController.java` → `topUp()`  
  - `src/main/java/com/example/auctions/service/WalletService.java` → `topUp()`  
  - `src/main/resources/templates/wallet/index.html`
- **Mô tả vấn đề:**  
  User chỉ cần POST amount > 0 là balance tăng ngay. Không có VNPay callback, không có chữ ký gateway, không có admin duyệt, không có limit. README còn ghi “VNPay integration”, nhưng source hiện tại không có luồng thanh toán gateway tương ứng.
- **Kịch bản khai thác hoặc cách lỗi xảy ra:**  
  Người test mở `/wallet`, nạp 999,999,999 VNĐ rồi đi bid/pay. Có thể spam top-up liên tục.
- **Hậu quả:**  
  Toàn bộ logic đấu giá và thanh toán mất giá trị; bất kỳ ai cũng có “tiền ảo vô hạn”.
- **Cách sửa cụ thể:**  
  - Cách nhanh nhất trước demo: **tắt top-up tự do**, hoặc chỉ cho admin seed balance test.  
  - Nếu giữ top-up: phải có transaction gateway/callback có verify chữ ký + idempotency.  
  - Thêm daily cap, cooldown, audit, rate limit.
- **Ưu tiên sửa:** **ngay**

---

### 2.5. Place bid có race condition, nhận bid muộn, không giữ tiền, và có thể bị “bid lẻ” khó thấy

- **Tiêu đề lỗi:** Bidding logic không an toàn khi concurrent
- **Mức độ:** **Critical**
- **Nhóm lỗi:** Concurrency / Data integrity / Business logic
- **Vị trí:**  
  - `src/main/java/com/example/auctions/controller/BidController.java` → `placeBid()`  
  - `src/main/java/com/example/auctions/service/BidService.java` → `placeBid()`  
  - `src/main/resources/templates/bids/auction-bids.html`  
  - `src/main/java/com/example/auctions/model/Auction.java` → `isEndTimeValid()`
- **Mô tả vấn đề:**  
  `BidService.placeBid()`:
  - không lock auction row
  - không dùng `@Version`
  - đọc `auction.getCurrentPrice()` từ object truyền vào
  - chỉ check `amount > currentPrice`
  - không check `endTime > now`
  - không reserve balance
  - không enforce increment server-side  
  Ngoài ra form bid dùng input text; backend nhận `BigDecimal`, frontend lại format/round kiểu số nguyên.
- **Kịch bản khai thác hoặc cách lỗi xảy ra trong thực tế:**  
  1. Hai người gửi bid song song, cả hai cùng pass validation.  
  2. Bid thấp hơn có thể save sau và overwrite `currentPrice`.  
  3. Bid sau khi `endTime` đã qua vẫn có thể được nhận cho đến khi scheduler 5 giây/lần đóng auction.  
  4. Một user có 100k vẫn có thể đứng đầu ở nhiều auction cùng lúc vì không có fund reservation.  
  5. Có thể gửi crafted request kiểu `100000.01`; backend nhận, còn UI nhiều chỗ `Math.round()`/`formatInteger()` nên hiển thị dễ gây tranh cãi.
- **Hậu quả:**  
  Sai current price, sai winner, seller khiếu nại, lớp có thể spam song song để kiếm lỗi logic rất dễ.
- **Cách sửa cụ thể:**  
  - Tải lại auction trong transaction bằng **row lock** (`PESSIMISTIC_WRITE`) hoặc `@Version` + retry.  
  - Re-check tất cả điều kiện ở **service**: active, not owner, private access, `endTime > now`.  
  - Enforce min increment ở server.  
  - Dùng kiểu tiền nhất quán cho VNĐ: `DECIMAL(...,0)` hoặc `BIGINT`.  
  - Cân nhắc reserve/hold balance khi user đang là highest bidder.
- **Ưu tiên sửa:** **ngay**

---

### 2.6. Transaction có thể bị tạo trùng và payment có thể bị double-submit

- **Tiêu đề lỗi:** Duplicate transaction / double payment / state-changing GET
- **Mức độ:** **Critical**
- **Nhóm lỗi:** Concurrency / Data integrity / Security
- **Vị trí:**  
  - `src/main/java/com/example/auctions/controller/TransactionController.java` → `showTransactionConfirmation()`, `payWithWallet()`  
  - `src/main/java/com/example/auctions/service/TransactionService.java` → `findOrCreateTransaction()`  
  - `src/main/java/com/example/auctions/service/WalletService.java` → `payForAuction()`  
  - `src/main/java/com/example/auctions/repository/TransactionRepository.java`
- **Mô tả vấn đề:**  
  - `GET /transactions/create/{auctionId}` có side effect: có thể tạo transaction mới.  
  - `findOrCreateTransaction()` là pattern read-then-insert, **không có unique constraint** trên `auction_id`.  
  - `payForAuction()` không lock transaction/user rows, không idempotent.
- **Kịch bản khai thác hoặc cách lỗi xảy ra:**  
  - Mở nhiều tab confirm cùng lúc để tạo nhiều `PENDING` transaction cho cùng auction.  
  - Spam song song `POST /transactions/pay/{id}` để double-pay hoặc làm ledger inconsistent.
- **Hậu quả:**  
  Có thể sinh nhiều transaction cho 1 auction, seller được cộng tiền sai, audit ví sai, hệ thống rơi vào trạng thái khó giải trình.
- **Cách sửa cụ thể:**  
  - `transactions.auction_id` phải là **UNIQUE NOT NULL** + FK.  
  - `findOrCreate` phải dựa trên DB uniqueness, không chỉ dựa trên Optional.  
  - `payForAuction` phải lock `transaction`, `buyer`, `seller`; có state transition idempotent (`PENDING -> PROCESSING -> COMPLETED`).  
  - Không tạo dữ liệu mới trong GET; chuyển sang POST hoặc tạo khi auction end bằng logic idempotent.
- **Ưu tiên sửa:** **ngay**

---

### 2.7. Verification code/private code quá yếu, không rate-limit, dễ brute-force/spam

- **Tiêu đề lỗi:** Email verification và private auction unlock dễ brute-force
- **Mức độ:** **High**
- **Nhóm lỗi:** Security
- **Vị trí:**  
  - `src/main/java/com/example/auctions/service/UserService.java` → `registerUser()`, `resendVerificationCode()`, `verifyUser()`  
  - `src/main/java/com/example/auctions/service/AuctionService.java` → `unlockPrivateAuction()`  
  - `src/main/java/com/example/auctions/model/AuctionAccessCode.java`
- **Mô tả vấn đề:**  
  - Verification code chỉ 5 chữ số, dùng `Random`, không expiry, không attempt limit, không cooldown resend.  
  - `/register/resend` lộ “User not found” và “already verified” → email enumeration.  
  - Private auction code lưu plaintext, compare case-insensitive, không expiry, không lock attempt.
- **Kịch bản khai thác hoặc cách lỗi xảy ra:**  
  100–150 người trong lớp đủ để brute-force 100,000 mã với account/email biết trước. Tương tự có thể flood email hoặc brute-force private auction code.
- **Hậu quả:**  
  Verify trái phép, bypass private auction, spam email, lộ trạng thái account.
- **Cách sửa cụ thể:**  
  - Dùng `SecureRandom`; mã dài hơn hoặc token khó đoán.  
  - Thêm expiry 5–10 phút.  
  - Lưu hash của code/token, không log raw value.  
  - Cooldown resend, limit số lần thử, lock theo IP/email/session.  
  - Response generic, không lộ account state.
- **Ưu tiên sửa:** **ngay / trước deploy**

---

### 2.8. JWT/session/OAuth còn lỗ hổng kiểm soát phiên và trạng thái user

- **Tiêu đề lỗi:** Disabled user vẫn có thể sống bằng JWT; custom sign-in không rotate session
- **Mức độ:** **High**
- **Nhóm lỗi:** Authentication / Session / OAuth
- **Vị trí:**  
  - `src/main/java/com/example/auctions/security/JwtAuthenticationFilter.java`  
  - `src/main/java/com/example/auctions/service/AuthenticationSessionService.java`  
  - `src/main/java/com/example/auctions/security/GoogleOAuth2SuccessHandler.java`
- **Mô tả vấn đề:**  
  - JWT filter validate chữ ký rồi load user theo username, nhưng không check rõ `isEnabled()`/lock/token version.  
  - Admin disable user không revoke JWT hiện có.  
  - `AuthenticationSessionService.signIn()` tạo session nhưng không `changeSessionId()`.  
  - Google success handler auto-link account theo email hiện có; **tôi nghi ngờ** cần kiểm tra thêm `email_verified`, hiện code không check thuộc tính này.
- **Kịch bản khai thác hoặc cách lỗi xảy ra:**  
  - User bị disable vẫn dùng JWT cũ đến hết hạn.  
  - Session fixation có thể xảy ra ở luồng sign-in custom/OAuth.  
  - Auto-link OAuth theo email mà không verify rõ ràng là một điểm cần harden thêm.
- **Hậu quả:**  
  Tắt account không có hiệu lực ngay; phiên bị cướp; rủi ro account linking sai.
- **Cách sửa cụ thể:**  
  - Trong JWT filter, từ chối user disabled/locked.  
  - Thêm `tokenVersion` hoặc check `passwordChangedAt`.  
  - `request.changeSessionId()` khi sign-in custom.  
  - Kiểm tra `email_verified=true` trước khi auto-link Google account.  
  - Có cơ chế invalidate session/JWT khi admin disable user.
- **Ưu tiên sửa:** **trước khi demo**

---

### 2.9. Private auction hiện tại chỉ “nửa kín nửa hở”

- **Tiêu đề lỗi:** Private auction lộ metadata và có thể bị nghe WebSocket trái phép
- **Mức độ:** **High**
- **Nhóm lỗi:** Authorization / Data privacy / WebSocket
- **Vị trí:**  
  - `src/main/java/com/example/auctions/controller/BrowseController.java`  
  - `src/main/java/com/example/auctions/service/AuctionService.java` → `searchActiveAuctions()`, `canAccessPrivateAuction()`, `unlockPrivateAuction()`  
  - `src/main/java/com/example/auctions/repository/AuctionRepository.java` → `searchActiveAuctions()`  
  - `src/main/resources/templates/auctions/details.html`  
  - `src/main/java/com/example/auctions/service/BidService.java` → `notifyNewBid()`
- **Mô tả vấn đề:**  
  Private auction vẫn xuất hiện trong browse/search và trang detail vẫn lộ:
  - tên sản phẩm
  - mô tả
  - ảnh
  - tên seller  
  Chỉ contact info và nút bid là bị chặn. Ngoài ra bid event phát lên topic công khai `/topic/auction/{id}`.
- **Kịch bản khai thác hoặc cách lỗi xảy ra:**  
  Một user bất kỳ có thể duyệt private listings, lấy auction ID, rồi tự subscribe STOMP vào topic của auction để theo dõi event. Trong code hiện tại không có authz ở tầng subscribe/message.
- **Hậu quả:**  
  “Private auction” không private theo nghĩa chặt; dễ bị giảng viên bắt lỗi thiết kế/phân quyền.
- **Cách sửa cụ thể:**  
  - Quyết định lại business rule: private là ẩn hoàn toàn hay chỉ ẩn bid/contact.  
  - Nếu thật sự private: ẩn khỏi browse/search/detail cho user chưa được phép.  
  - Không dùng public topic cho private bid data.  
  - Dùng access table/invitation thay vì shared code đơn giản.
- **Ưu tiên sửa:** **trước khi demo**

---

### 2.10. Stored DOM XSS qua WebSocket message

- **Tiêu đề lỗi:** Dữ liệu user-controlled được inject vào HTML động
- **Mức độ:** **High**
- **Nhóm lỗi:** Security / XSS
- **Vị trí:**  
  - `src/main/resources/templates/bids/auction-bids.html`  
  - `src/main/resources/templates/auctions/details.html`
- **Mô tả vấn đề:**  
  Các page này dùng `innerHTML`/`insertAdjacentHTML` để render `bidderName`, `winnerName`, `productName` từ WebSocket payload. Những dữ liệu này bắt nguồn từ user input (`fullName`, `productName`) và không được sanitize trước khi đưa vào DOM.
- **Kịch bản khai thác hoặc cách lỗi xảy ra:**  
  Attacker tạo user full name hoặc product name chứa payload HTML/JS, sau đó place bid hoặc kết thúc auction. Khi người khác mở trang bid/detail, payload có thể execute.
- **Hậu quả:**  
  XSS stored/DOM, đánh cắp session, thao túng UI, cướp admin session nếu admin mở trang liên quan.
- **Cách sửa cụ thể:**  
  - Không dùng `innerHTML` với dữ liệu động.  
  - Dùng `textContent`, `createElement`, DOM API an toàn.  
  - Bổ sung CSP ở production.
- **Ưu tiên sửa:** **ngay**

---

### 2.11. Chọn winner khi tie amount đang không deterministic, scheduler có thể vấp cả batch

- **Tiêu đề lỗi:** Query highest bid có thể trả về nhiều bản ghi
- **Mức độ:** **High**
- **Nhóm lỗi:** Data integrity / Concurrency / Bug
- **Vị trí:**  
  - `src/main/java/com/example/auctions/repository/BidRepository.java` → `findTopByAuctionOrderByAmountDesc()`  
  - `src/main/java/com/example/auctions/service/AuctionService.java` → `updateExpiredAuctions()`, `endAuction()`
- **Mô tả vấn đề:**  
  Query highest bid hiện tại lấy tất cả bid có `amount = MAX(amount)` nhưng method lại trả `Optional<Bid>`. Nếu có 2 bid cùng max amount thì query có thể trả nhiều dòng, dẫn đến exception hoặc winner không rõ ràng.
- **Kịch bản khai thác hoặc cách lỗi xảy ra:**  
  Hai user bid cùng mức cao nhất, đặc biệt khi spam song song. Auction end sẽ rơi vào trạng thái lỗi/khó đoán.
- **Hậu quả:**  
  Sai winner, list “won auctions” sai, scheduler close auction có thể fail. Vì `updateExpiredAuctions()` catch ở ngoài loop, một auction xấu có thể ảnh hưởng cả lượt chạy.
- **Cách sửa cụ thể:**  
  - Định nghĩa rule tie rõ ràng: ví dụ `ORDER BY amount DESC, bidTime ASC, id ASC LIMIT 1`.  
  - Chuyển repository sang query trả 1 row deterministic.  
  - Catch lỗi theo từng auction trong loop scheduler.
- **Ưu tiên sửa:** **trước khi demo**

---

### 2.12. Upload/file path chưa an toàn và gần như chắc chắn hỏng trên Linux

- **Tiêu đề lỗi:** Upload path kiểu Windows + validate file yếu
- **Mức độ:** **High**
- **Nhóm lỗi:** Deployment / File upload / Bug
- **Vị trí:**  
  - `src/main/resources/application.properties`  
  - `src/main/java/com/example/auctions/service/AuctionService.java` → `saveImage()`  
  - `src/main/java/com/example/auctions/controller/ImageController.java`  
  - `src/main/java/com/example/auctions/config/WebConfig.java`
- **Mô tả vấn đề:**  
  `auction.images.upload.path=C:/SourceCode/auction-images`. Đây là path Windows. Khi lên Linux container, path này rất dễ sai/hỏng. `saveImage()` còn assume filename có dấu `.` để lấy extension; trường hợp file tên lạ có thể throw exception.
- **Kịch bản khai thác hoặc cách lỗi xảy ra:**  
  - Deploy lên VPS Linux: upload ảnh không chạy, ảnh không load, thư mục tạo sai chỗ.  
  - Attacker upload file giả ảnh với MIME/type spoof.
- **Hậu quả:**  
  Demo lỗi upload, ảnh mất sau restart, storage path lộn xộn.
- **Cách sửa cụ thể:**  
  - Đổi sang path env như `/data/auction-images`.  
  - Mount volume Docker cho uploads.  
  - Validate magic bytes; chuẩn hóa tên file; xử lý case không có extension.  
  - Khi serve file, dùng `normalize()` + kiểm tra path nằm trong upload root.
- **Ưu tiên sửa:** **ngay / trước deploy**

---

### 2.13. Project chưa sẵn sàng Docker/Linux/production

- **Tiêu đề lỗi:** Thiếu artifact deploy và config production đang rất nguy hiểm
- **Mức độ:** **High**
- **Nhóm lỗi:** Deployment / Production readiness
- **Vị trí:**  
  - **Không thấy** `Dockerfile`, `docker-compose.yml`, `compose.yml`, `nginx.conf`, `Caddyfile` trong zip  
  - `src/main/resources/application.properties`  
  - `src/main/java/com/example/auctions/config/WebSocketConfig.java`  
  - `src/main/java/com/example/auctions/config/AdminBootstrapConfig.java`  
  - `src/main/java/com/example/auctions/config/DataMigrationConfig.java`  
  - `src/main/java/com/example/auctions/service/EmailService.java`
- **Mô tả vấn đề:**  
  Nhiều dấu hiệu “dev mode” còn bật:
  - `ddl-auto=update`
  - `show-sql=true`
  - `thymeleaf.cache=false`
  - `allow-circular-references=true`
  - DB `root` password rỗng
  - `auction.server.url=http://localhost:8123`
  - WebSocket chỉ allow origin `http://localhost:8080`
  - bootstrap admin bật
  - startup SQL mutation chạy mỗi lần boot  
  Ngoài ra `EmailService.sendAuctionWonEmail/sendInvoiceEmail/sendPaymentNotificationToSeller()` đang **bị disable**, chỉ log chứ không gửi.
- **Kịch bản khai thác hoặc cách lỗi xảy ra:**  
  - WebSocket rất có thể fail ngay trên host thật vì origin không khớp.  
  - Reset password email sẽ chứa URL localhost → user không reset được trên server thật.  
  - Schema bị drift vì `ddl-auto=update`.  
  - Thiếu Docker assets → không có đường deploy chuẩn.
- **Hậu quả:**  
  Fail demo/deploy, schema khó kiểm soát, production hardening gần như chưa có.
- **Cách sửa cụ thể:**  
  - Tách `application-prod.yml`.  
  - Tạo Dockerfile/compose chuẩn.  
  - Dùng env cho domain/DB/mail/oauth/jwt/upload path.  
  - Bỏ `ddl-auto=update`, bỏ bootstrap admin sau seed đầu tiên, bỏ startup SQL hack.  
  - Fix WebSocket allowed origin theo domain thật hoặc same-origin config phù hợp.
- **Ưu tiên sửa:** **ngay**

---

### 2.14. DB schema còn thiếu constraint/index quan trọng, kiểu tiền chưa chuẩn

- **Tiêu đề lỗi:** Thiếu ràng buộc toàn vẹn và index cho các flow nóng
- **Mức độ:** **High**
- **Nhóm lỗi:** Database / Data integrity / Performance
- **Vị trí:**  
  - `src/main/java/com/example/auctions/model/User.java`  
  - `src/main/java/com/example/auctions/model/Auction.java`  
  - `src/main/java/com/example/auctions/model/Bid.java`  
  - `src/main/java/com/example/auctions/model/Transaction.java`  
  - `src/main/java/com/example/auctions/model/WalletTransaction.java`
- **Mô tả vấn đề:**  
  - Không có `@Version` ở `User`, `Auction`, `Transaction`.  
  - Nhiều field tiền dùng `BigDecimal` nhưng không set precision/scale.  
  - `Transaction.auctionId` chỉ là `Long`, **không phải FK**, **không UNIQUE**.  
  - `WalletTransaction.relatedTransactionId/relatedAuctionId` cũng không có FK.  
  - Thiếu index ở các query nóng.
- **Kịch bản khai thác hoặc cách lỗi xảy ra:**  
  Under load sẽ có lost update, duplicate transaction, query chậm, money không nhất quán, audit khó tin.
- **Hậu quả:**  
  Dữ liệu inconsistent, hiệu năng tệ, rất khó xử lý tranh chấp sau demo.
- **Cách sửa cụ thể:**  
  Nên thêm tối thiểu:
  - `transactions.auction_id UNIQUE NOT NULL` + FK sang `auctions(id)`  
  - index `auctions(status, end_time)`  
  - index `auctions(seller_id, status, end_time)`  
  - index `bids(auction_id, amount, bid_time)`  
  - index `bids(bidder_id, bid_time)`  
  - index `wallet_transactions(user_id, created_at)`  
  - `CHECK amount > 0`, `CHECK balance >= 0`  
  - dùng `DECIMAL(19,0)` hoặc `BIGINT` cho VNĐ  
  - thêm `@Version`
- **Ưu tiên sửa:** **trước deploy**  
  Riêng `transactions.auction_id UNIQUE` nên sửa **ngay**

---

### 2.15. Không có rate limit thực chất; 100–150 người spam là đủ gây lỗi

- **Tiêu đề lỗi:** Spam resistance rất yếu
- **Mức độ:** **High**
- **Nhóm lỗi:** Performance / Security / Abuse resistance
- **Vị trí:**  
  - `/login`, `/api/auth/login`  
  - `/register`, `/register/verify`, `/register/resend`  
  - `/forgot-password`, `/reset-password`  
  - `/wallet/top-up`  
  - `/bids/place`  
  - `/auctions/{id}/unlock`
- **Mô tả vấn đề:**  
  Tôi không thấy rate limiting, captcha, cooldown, account lockout, per-IP/per-user throttling cho các endpoint nhạy cảm.
- **Kịch bản khai thác hoặc cách lỗi xảy ra:**  
  Cả lớp spam login, resend verify, unlock, bid, top-up, upload ảnh hoặc brute-force code.
- **Hậu quả:**  
  DB load tăng, email quota cháy, brute-force thành công, app lag/timeout.
- **Cách sửa cụ thể:**  
  - Dùng rate limit ở reverse proxy trước: Nginx/Caddy/Traefik.  
  - App-level limit cho verify/unlock/login.  
  - Clamp page size và upload frequency.  
  - Có cooldown hiển thị ở UI nhưng enforce ở backend.
- **Ưu tiên sửa:** **trước khi demo**

---

### 2.16. Điểm nghẽn performance rõ ràng khi lên khoảng 100–150 người

- **Tiêu đề lỗi:** Có các điểm N+1 / query dư / config chậm trong code hiện tại
- **Mức độ:** **Medium**
- **Nhóm lỗi:** Performance
- **Vị trí:**  
  - `src/main/java/com/example/auctions/controller/GlobalModelAdvice.java` → `walletBalance()`  
  - `src/main/java/com/example/auctions/controller/AuctionManagementController.java` → vòng lặp `auction.getBids()`  
  - `src/main/java/com/example/auctions/service/AdminService.java` → `getUserDetail()`  
  - `src/main/resources/application.properties`
- **Mô tả vấn đề:**  
  - `walletBalance()` query DB trên gần như mọi page render.  
  - Seller/admin pages có pattern load rồi xử lý list trong loop.  
  - `AdminService.getUserDetail()` có chỗ `findBySellerId(userId).size()` và load tất cả bid rồi subList 5 dòng.  
  - `show-sql=true`, `thymeleaf.cache=false` làm app chậm hơn cần thiết.
- **Kịch bản khai thác hoặc cách lỗi xảy ra:**  
  Chỉ cần 100–150 người browse/list/history/dashboard cùng lúc là đã thấy chậm, nhất là khi query search `%keyword%` không có index tốt.
- **Hậu quả:**  
  Lag UI, timeout, DB pool nghẽn.
- **Cách sửa cụ thể:**  
  - Bật cache Thymeleaf ở prod.  
  - Tắt `show-sql`.  
  - Tối ưu query/paging/fetch join.  
  - Không load all rồi mới `subList`.  
  - Clamp `size <= 50`.  
  - Tune Hikari pool thay vì để mặc định.
- **Ưu tiên sửa:** **trước khi demo**

---

### 2.17. Test coverage và observability chưa đủ để tự vệ khi bị hỏi vặn

- **Tiêu đề lỗi:** Thiếu test/security test/concurrency test/audit log
- **Mức độ:** **Medium**
- **Nhóm lỗi:** Testing / Observability
- **Vị trí:**  
  - `src/test/java/...` (hiện có 5 class, khoảng 13 test)  
  - Toàn hệ thống thiếu actuator/audit trail/metrics
- **Mô tả vấn đề:**  
  Có test tích hợp cơ bản cho browse/private access/google onboarding/JWT/admin UI, nhưng **thiếu hoàn toàn** test cho các flow nguy hiểm nhất:
  - register role tampering
  - profile IDOR/password takeover
  - concurrent bid
  - concurrent payment
  - duplicate transaction
  - JWT của user disabled
  - brute-force verify/unlock
  - XSS regression
- **Kịch bản khai thác hoặc cách lỗi xảy ra:**  
  Khi giảng viên hỏi “làm sao chứng minh không double-pay / không race / không IDOR?”, hiện tại gần như không có bằng chứng test/audit đủ mạnh.
- **Hậu quả:**  
  Khó bảo vệ bài khi bị soi kỹ; khó debug khi lớp phá thật.
- **Cách sửa cụ thể:**  
  Must-have trước demo:
  - integration test cho register role tampering  
  - integration test cho profile only-current-user  
  - concurrency test cho bid/payment/top-up  
  - security test cho disabled JWT, brute force throttling  
  - audit log có `userId`, `auctionId`, `transactionId`, `ip`, `action`, `status`
- **Ưu tiên sửa:** **trước khi demo**

---

### 2.18. Ghi chú cân bằng: cái gì tôi chưa thấy

- **SQL Injection / JPQL injection trực tiếp:** **chưa thấy rõ** trong repository hiện tại; query chủ yếu dùng bind params.  
- **CORS quá rộng:** **không thấy**; vấn đề chính là WebSocket origin đang **quá hẹp và sai cho production**.  
- **H2 console lộ prod:** config có bật trong `application.properties`, nhưng `pom.xml` chỉ có H2 ở `test` scope; vì vậy đây là **dấu hiệu config dev còn sót**, chưa đủ cơ sở để kết luận prod đang lộ H2 console.  
- **CSRF:** các form Thymeleaf có token khá đầy đủ; lỗ hổng CSRF nổi bật nhất hiện tại là **state-changing GET** ở transaction create, chứ không phải tắt CSRF toàn cục.

---

### Bản đồ race condition / spam hotspot đáng chú ý

| Điểm nóng | Điều có thể xảy ra | Mức độ | Có thể bị khai thác để kiếm điểm? | Fix chuẩn |
|---|---|---|---|---|
| `POST /bids/place` | current price bị ghi đè sai, nhận bid muộn, tie/winner sai | Critical | Rất dễ, chỉ cần spam song song | `@Transactional` + row lock auction hoặc `@Version` + retry |
| `POST /wallet/top-up` | mất update khi top-up song song; cộng tiền giả do design | Critical | Rất dễ | disable top-up tự do; lock user row; idempotency nếu có gateway |
| `GET /transactions/create/{auctionId}` | tạo nhiều `PENDING` transaction cho 1 auction | Critical | Dễ | UNIQUE `auction_id`, đổi sang POST/idempotent create |
| `POST /transactions/pay/{id}` | double submit, seller/buyer balance inconsistent | Critical | Rất dễ | lock transaction + buyer + seller; idempotency |
| `/register/verify` + `/register/resend` | brute-force code, overwrite code, spam mail | High | Dễ | expiry + attempts + cooldown + rate limit |
| `/auctions/{id}/unlock` | brute-force private code, session unlock tồn tại lâu | High | Dễ | hashed code + cooldown + attempt tracking |
| `/login` + `/api/auth/login` | brute-force password | High | Dễ | rate limit + cooldown + captcha sau threshold |
| scheduler `updateExpiredAuctions()` | 1 auction lỗi có thể ảnh hưởng cả batch | High | Khá dễ kích hoạt bằng tie/race | catch per-auction, query deterministic |

---

## 3. BẢNG ƯU TIÊN FIX

### Sửa trong hôm nay

| Việc cần làm | Vì sao |
|---|---|
| Chặn server-side `role=ADMIN` ở đăng ký | Leo quyền trực tiếp |
| Bỏ hidden `id/password/role/email` khỏi profile, chỉ update current user | Account takeover trực tiếp |
| Tắt bootstrap admin mặc định, đổi toàn bộ secret ra env, rotate secret đã lộ | Lộ admin/JWT/mail/oauth |
| Tắt top-up tự do hoặc đổi thành admin-seeded balance cho demo | Tránh tiền giả |
| Lock/idempotent cho bid + pay + create transaction | Chặn race nghiêm trọng nhất |
| Fix WebSocket origin và bỏ `innerHTML` nguy hiểm | WebSocket hiện dễ hỏng + XSS |
| Đổi upload path sang Linux-friendly path/volume | Tránh upload chết trên VPS |
| Tắt `show-sql`, bật cache Thymeleaf ở prod | Giảm lag ngay |

### Sửa trước khi demo

| Việc cần làm | Vì sao |
|---|---|
| Thêm rate limit/cooldown cho login, verify, resend, unlock, top-up, bid | Chống lớp spam/brute-force |
| Thêm `transactions.auction_id UNIQUE` + FK | Chặn duplicate transaction |
| Thêm `@Version`/locking cho `User`, `Auction`, `Transaction` | Chống lost update |
| Enforce `endTime > now` ở service khi bid; reject late bid | Chống bid sau giờ |
| Fix deterministic winner query khi tie | Chống sai winner |
| Quyết định lại private auction: ẩn hoàn toàn hay không | Tránh bị bắt lỗi “private mà vẫn lộ” |
| Thêm test cho role tampering, profile IDOR, concurrent bid/payment | Có cái để chứng minh |
| Tạo Dockerfile + compose + prod profile + reverse proxy | Có đường deploy chuẩn |

### Sửa sau khi demo

| Việc cần làm | Vì sao |
|---|---|
| Đưa schema về Flyway/Liquibase thật sự | Kiểm soát migration |
| Thêm audit trail/admin moderation log/failed login log | Điều tra tranh chấp |
| CSP/HSTS/Referrer-Policy/security header hardening | Hardening dài hạn |
| Token revocation/token version/session registry | Quản lý phiên tốt hơn |
| Tối ưu search/index/fulltext nếu dataset lớn | Scale tốt hơn |
| Tách rõ domain service/payment service/outbox email | Kiến trúc sạch hơn |

---

## 4. CHECKLIST DEPLOY DOCKER + VPS LINUX

### 4.1. Những gì sẽ hỏng ngay nếu mang project hiện tại lên Docker + Linux

1. **Không thấy Dockerfile/compose** trong zip, nên hiện chưa có đường build/run production rõ ràng.
2. **Upload path kiểu Windows**: `C:/SourceCode/auction-images` gần như chắc chắn lỗi trên Linux.
3. **WebSocket origin đang là `http://localhost:8080`**; app thực chạy ở `8123` hoặc domain thật có thể không connect được.
4. **Reset password URL đang hard-code localhost**; email reset sẽ trỏ sai.
5. **DB config dùng root/password rỗng**; không phù hợp container/prod.
6. **`ddl-auto=update` + Flyway disabled**; schema prod khó kiểm soát.
7. **Uploads không có volume mount chuẩn**; restart container có thể mất file.
8. **Không có healthcheck/readiness/restart policy**.
9. **Email winner/payment đang bị disable**; feature mô tả trong README sẽ không hoạt động.
10. **Bootstrap admin và startup SQL mutation** có thể gây tác dụng phụ khi container restart.

### 4.2. Cấu hình production tối thiểu nên có

1. **`application-prod.yml`** tách khỏi dev:
   - datasource URL/user/password qua env
   - JWT secret qua env
   - mail/oauth secret qua env
   - `auction.server.url=https://your-domain`
   - `auction.images.upload.path=/data/auction-images`
   - `ddl-auto=validate` hoặc `none`
   - `thymeleaf.cache=true`
   - `show-sql=false`

2. **Dockerfile multi-stage**, chạy bằng non-root user:
   - build jar
   - runtime image JRE
   - user không phải root
   - `VOLUME /data/auction-images`

3. **docker-compose** tối thiểu:
   - `app`
   - `mysql`
   - named volume cho MySQL
   - named/bind volume cho uploads
   - `restart: unless-stopped`
   - healthcheck cho app và mysql
   - DB port **không public** nếu không cần

4. **Reverse proxy** (Nginx/Caddy):
   - HTTPS
   - WebSocket upgrade
   - rate limit login/verify/unlock/bid/top-up
   - static asset caching/gzip
   - proxy headers / forwarded headers

5. **DB user riêng cho app**, không dùng root:
   - chỉ quyền cần thiết
   - không `createDatabaseIfNotExist=true` trên prod

6. **Backup strategy**:
   - dump MySQL hàng ngày
   - backup volume ảnh upload
   - retain ít nhất 7 bản gần nhất

### 4.3. Checklist hardening VPS

- UFW chỉ mở `22`, `80`, `443`
- Không expose MySQL public
- Tắt SSH password login nếu có thể, dùng key
- Cấm root login SSH
- Cài `fail2ban`
- Bật log rotation cho Docker/container logs
- Bật update bảo mật tự động ở mức OS
- Theo dõi disk usage cho volume upload + MySQL
- Dùng domain thật + TLS hợp lệ
- Thiết lập `server.forward-headers-strategy=framework` hoặc tương đương nếu sau proxy
- Rate limit ở proxy cho `/login`, `/api/auth/login`, `/register/*`, `/forgot-password`, `/wallet/top-up`, `/bids/place`, `/auctions/*/unlock`
- Không commit `.env` lên repo

---

## 5. TOP 10 CÁCH MÀ SINH VIÊN KHÁC CÓ THỂ VÀO “KIẾM ĐIỂM”

1. **Sửa request đăng ký thành `role=ADMIN`** rồi verify account.
2. **Sửa hidden `id` trong `/profile`** để đổi thông tin/mật khẩu user khác.
3. **Đăng nhập bằng admin mặc định** hoặc forge JWT nếu biết source/config.
4. **Nạp ví tiền giả** rồi bid/pay như thật.
5. **Bắn 2–10 request `place bid` song song** để làm current price/winner sai.
6. **Spam `transactions/create` và `transactions/pay`** để tạo transaction trùng hoặc double-pay.
7. **Brute-force mã verify 5 số** hoặc spam resend để enumerate email.
8. **Brute-force mã private auction** và/hoặc subscribe STOMP trực tiếp vào topic auction.
9. **Tạo tên người dùng/tên sản phẩm chứa HTML/JS** để kích hoạt XSS trên trang bid/detail.
10. **Gọi các page với `size` rất lớn / spam login/unlock/upload** để làm app chậm hoặc timeout.

---

## 6. NẾU CÓ THỂ, HÃY ĐỀ XUẤT PATCH

### 6.1. Patch nhanh cho đăng ký: bỏ bind entity, whitelist role

```java
public record RegisterRequest(
    @NotBlank String fullName,
    @Email @NotBlank String email,
    @NotBlank String phoneNumber,
    @NotBlank String password,
    @NotNull UserRole role
) {}

@PostMapping("/register")
public String registerUser(@Valid @ModelAttribute("form") RegisterRequest req,
                           BindingResult result) {
    if (result.hasErrors()) return "register";

    if (req.role() != UserRole.BUYER && req.role() != UserRole.SELLER) {
        result.rejectValue("role", "invalid", "Invalid role");
        return "register";
    }

    userService.registerUser(req);
    return "redirect:/login?registered";
}
```

```java
@Transactional
public User registerUser(RegisterRequest req) {
    if (userRepository.existsByEmail(req.email())) {
        throw new RuntimeException("Email already registered");
    }

    User user = new User();
    user.setFullName(req.fullName().trim());
    user.setEmail(req.email().trim().toLowerCase());
    user.setPhoneNumber(req.phoneNumber().trim());
    user.setPassword(passwordEncoder.encode(req.password()));
    user.setRole(req.role()); // only BUYER/SELLER already whitelisted
    user.setBalance(BigDecimal.ZERO);
    user.setEnabled(false);
    // generate secure expiring token...
    return userRepository.save(user);
}
```

---

### 6.2. Patch nhanh cho profile: chỉ update current user, tách luồng đổi mật khẩu

```java
public record ProfileUpdateRequest(
    @NotBlank String fullName,
    @NotBlank String phoneNumber
) {}

public record ChangePasswordRequest(
    @NotBlank String currentPassword,
    @NotBlank String newPassword
) {}

@PostMapping("/profile")
public String updateProfile(@AuthenticationPrincipal User currentUser,
                            @Valid @ModelAttribute("form") ProfileUpdateRequest form,
                            BindingResult result) {
    if (result.hasErrors()) return "profile";
    userService.updateProfile(currentUser.getId(), form);
    return "redirect:/profile?updated";
}

@PostMapping("/profile/password")
public String changePassword(@AuthenticationPrincipal User currentUser,
                             @Valid @ModelAttribute("form") ChangePasswordRequest form,
                             BindingResult result) {
    if (result.hasErrors()) return "profile";
    userService.changePassword(currentUser.getId(), form.currentPassword(), form.newPassword());
    return "redirect:/profile?passwordChanged";
}
```

```java
@Transactional
public void updateProfile(Long currentUserId, ProfileUpdateRequest form) {
    User user = userRepository.findById(currentUserId)
        .orElseThrow(() -> new RuntimeException("User not found"));
    user.setFullName(form.fullName().trim());
    user.setPhoneNumber(form.phoneNumber().trim());
}

@Transactional
public void changePassword(Long currentUserId, String currentPassword, String newPassword) {
    User user = userRepository.findById(currentUserId)
        .orElseThrow(() -> new RuntimeException("User not found"));
    if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
        throw new RuntimeException("Current password is incorrect");
    }
    validateStrongPassword(newPassword);
    user.setPassword(passwordEncoder.encode(newPassword));
}
```

---

### 6.3. Patch nhanh cho bidding: lock auction row + check end time ở service

```java
public interface AuctionRepository extends JpaRepository<Auction, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Auction a where a.id = :id")
    Optional<Auction> findByIdForUpdate(@Param("id") Long id);
}
```

```java
@Transactional
public Bid placeBid(Long auctionId, Long bidderId, BigDecimal amount, HttpSession session) {
    Auction auction = auctionRepository.findByIdForUpdate(auctionId)
        .orElseThrow(() -> new RuntimeException("Auction not found"));
    User bidder = userRepository.findById(bidderId)
        .orElseThrow(() -> new RuntimeException("User not found"));

    if (auction.getStatus() != AuctionStatus.ACTIVE || !auction.getEndTime().isAfter(LocalDateTime.now())) {
        throw new RuntimeException("Auction is already closed");
    }
    if (auction.getSeller().getId().equals(bidderId)) {
        throw new RuntimeException("You cannot bid on your own auction");
    }
    if (!auctionService.canAccessPrivateAuction(auction, bidder, session)) {
        throw new RuntimeException("Private auction access required");
    }

    BigDecimal current = auction.getCurrentPrice() != null ? auction.getCurrentPrice() : auction.getStartingPrice();
    BigDecimal minIncrement = new BigDecimal("1000");
    BigDecimal minAllowed = current.add(minIncrement);

    if (amount.scale() > 0) {
        throw new RuntimeException("Bid must be whole VND");
    }
    if (amount.compareTo(minAllowed) < 0) {
        throw new RuntimeException("Bid must be at least " + minAllowed);
    }

    // optional: check reserved balance / available balance here

    Bid bid = new Bid();
    bid.setAuction(auction);
    bid.setBidder(bidder);
    bid.setAmount(amount);
    bid.setBidTime(LocalDateTime.now());
    bidRepository.save(bid);

    auction.setCurrentPrice(amount);
    return bid;
}
```

---

### 6.4. Patch nhanh cho transaction/payment: uniqueness + lock + idempotent

```sql
ALTER TABLE transactions
    MODIFY auction_id BIGINT NOT NULL,
    ADD CONSTRAINT uk_transactions_auction UNIQUE (auction_id),
    ADD CONSTRAINT fk_transactions_auction
        FOREIGN KEY (auction_id) REFERENCES auctions(id);

CREATE INDEX idx_auctions_status_end_time ON auctions(status, end_time);
CREATE INDEX idx_bids_auction_amount_time ON bids(auction_id, amount, bid_time);
CREATE INDEX idx_wallet_user_created_at ON wallet_transactions(user_id, created_at);
```

```java
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transaction t left join fetch t.buyer left join fetch t.seller where t.id = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") Long id);

    Optional<Transaction> findByAuctionId(Long auctionId);
}
```

```java
@Transactional
public Transaction findOrCreateTransaction(Auction auction) {
    return transactionRepository.findByAuctionId(auction.getId())
        .orElseGet(() -> {
            Transaction tx = new Transaction();
            tx.setAuctionId(auction.getId());
            tx.setBuyer(auction.getWinner());
            tx.setSeller(auction.getSeller());
            tx.setPrice(auction.getCurrentPrice());
            tx.setStatus(TransactionStatus.PENDING);
            tx.setTransactionDate(LocalDateTime.now());
            return transactionRepository.save(tx);
        });
}

@Transactional
public boolean payForAuction(Long transactionId) {
    Transaction tx = transactionRepository.findByIdForUpdate(transactionId)
        .orElseThrow(() -> new RuntimeException("Transaction not found"));

    if (tx.getStatus() == TransactionStatus.COMPLETED) {
        return true; // idempotent
    }

    User buyer = userRepository.findByIdForUpdate(tx.getBuyer().getId())
        .orElseThrow(() -> new RuntimeException("Buyer not found"));
    User seller = userRepository.findByIdForUpdate(tx.getSeller().getId())
        .orElseThrow(() -> new RuntimeException("Seller not found"));

    if (buyer.getBalance().compareTo(tx.getPrice()) < 0) {
        return false;
    }

    buyer.setBalance(buyer.getBalance().subtract(tx.getPrice()));
    seller.setBalance(seller.getBalance().add(tx.getPrice()));
    tx.setStatus(TransactionStatus.COMPLETED);

    // save wallet ledger rows exactly once
    return true;
}
```

---

### 6.5. Patch nhanh cho XSS: bỏ `innerHTML`, dùng `textContent`

```javascript
function appendBidSafe(bid) {
  const bidItems = document.querySelector('.bid-items');

  const wrapper = document.createElement('div');
  wrapper.className = 'bid-item highest-bid';

  const left = document.createElement('div');

  const amount = document.createElement('div');
  amount.className = 'bid-amount';
  amount.textContent = `${Math.round(Number(bid.amount)).toLocaleString('vi-VN')} VNĐ`;

  const info = document.createElement('div');
  info.className = 'bid-info';
  info.textContent = `${bid.bidderName} • ${new Date(bid.timestamp).toLocaleString('vi-VN')}`;

  left.appendChild(amount);
  left.appendChild(info);

  const badge = document.createElement('div');
  badge.className = 'badge bg-success';
  badge.textContent = 'Highest Bid';

  wrapper.appendChild(left);
  wrapper.appendChild(badge);

  bidItems.prepend(wrapper);
}
```

---

## Kết luận cuối

**Không nên deploy project này ở trạng thái hiện tại.**  
Nếu chỉ xét dưới góc “làm bài môn học và bị cả lớp vào phá”, thì các lỗi **đủ nghiêm trọng để bị trừ điểm nặng** là:

- leo quyền admin khi đăng ký
- takeover account qua `/profile`
- ví nạp tiền giả
- race condition bid/payment
- hardcoded secret/default admin
- brute-force verify/private code
- private auction leak
- XSS

