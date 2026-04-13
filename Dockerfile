FROM eclipse-temurin:17-jre-jammy

# Tạo group và user để đảm bảo bảo mật
RUN groupadd -r appuser && useradd -r -g appuser -d /app appuser
WORKDIR /app

# Copy file JAR từ thư mục target/ vào container
COPY target/*.jar app.jar

# Cấu hình thư mục chứa ảnh và volume
RUN mkdir -p /data/auction-images && chown -R appuser:appuser /data/auction-images
VOLUME /data/auction-images

USER appuser
EXPOSE 8123

ENTRYPOINT ["java", "-jar", "app.jar"]
