# Online Auction System - Backend API

This repository contains the backend component of the Online Auction System. It is developed using **Java** and the **Spring Boot** framework, providing robust RESTful APIs for our mobile and web applications to handle authentications, bid processing, and auction management.

## 🚀 Technologies & Architecture

- **Framework**: Java Spring Boot
- **Security**: Spring Security & JWT (JSON Web Tokens)
- **Real-time Capabilities**: WebSocket for live bid synchronization
- **Database Architecture**: Relational Database (configured via `.env` / `application.properties`)
- **Containerization**: Docker & Docker Compose support

## 🔧 Features

- **User Authentication**: Secure Login/Registration with JWT token and Role-based access control (Admin, User).
- **Product & Auction Management**: Create, view, update, and manage product listings with precise auction time tracking.
- **Real-time Bidding**: Instantaneous bid placement and updates using WebSocket implementations.
- **Messaging System**: Built-in chat functionality allowing direct communication between users.
- **Admin Management Integration**: Specialized endpoints for system administrators to oversee and manage platform activities.

## 💻 Running the Application

### Local Setup (Using Maven)
1. Ensure you have **Java 17+** and **Maven** installed on your system.
2. Clone this repository and navigate into the project workspace.
3. Ensure your local database is running. Update the database connection credentials in `src/main/resources/application.properties` or configure your `.env` file based on `.env.example`.
4. Start the application:
   ```bash
   mvn spring-boot:run
   ```

### Docker Setup
To start the application and its dependencies (like the database) using Docker:
```bash
docker-compose up -d --build
```

The API will typically run on `http://localhost:8080`.
