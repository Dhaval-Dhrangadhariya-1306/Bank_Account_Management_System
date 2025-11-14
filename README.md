# 💳 Bank Management System – Java (JDK 21)

A complete **console-based Bank Management System** built using **Core Java**, featuring secure login, multi-account support, transactions, loans, fraud detection, admin panel, credit score, file storage, and more.

---

## 🚀 Features

### 🔐 User Login & Security
- Supports **Savings** and **Current** accounts  
- Secure **PIN verification**  
- **PIN retry limit (3 attempts)** → auto account lock  
- Change PIN feature  
- ATM card simulation: card number, CVV, expiry, OTP  
- Daily withdrawal limit and service charge deduction  

---

## 👨‍💼 Admin Panel
- Admin login with secure PIN  
- View all accounts  
- Freeze / deactivate user accounts  
- Modify interest rates  
- View complete transaction logs  
- View all ongoing loans  

---

## 💰 Banking Operations
- Deposit  
- Withdrawal  
- Fund transfer (User ↔ User, Savings ↔ Current)  
- Mini statement (last 5 transactions)  
- Full statement with **pagination** (10 entries per page)  
- Export statement as:
  - `.csv`
  - `.txt`

---

## 📈 Auto-Interest & Charges
- Monthly interest credit for Savings accounts  
- Current account maintenance fee deduction  
- Auto interest scheduler stored as transactions  

---

## 🏦 Loan Management System
- Multiple loan types:
  - Personal Loan  
  - Home Loan  
  - Education Loan  
- EMI calculation with:
  - Principal  
  - Interest  
  - Remaining balance  
- Loan prepayment (with/without penalty)  
- Multiple active loans per customer  

---

## 📊 Credit Score System
Score based on:
- Loan repayment history  
- Transaction behavior  
- Account age  
- Overdraft frequency  

---

## 🔍 Fraud Detection
Detects:
- High-value withdrawals  
- Suspicious rapid transactions  
- Unusual transfer patterns  
- Flags account & alerts admin  

---

## 🌈 Console UI
Uses ANSI colors for:
- Success messages  
- Errors  
- Warnings  
- Menu design  

---

## 🗂 Data Storage
Uses `.dat` files to store:
- Accounts  
- Transactions  
- Loans  
- Admin settings  

All data persists even after restarting the application.

---
