// BankSystem.java
// Enhanced bank system with admin, PIN change, freeze/close, card & OTP, withdrawal limits,
// transfers, CSV export, colored console, pagination, monthly interest/charges, multiple loans,
// EMI breakdown, prepayment, fraud detection and internal credit score.
//
// Compile: javac BankSystem.java
// Run:     java BankSystem

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class BankSystem {

    // ---------- Config ----------
    static final String ACCOUNTS_FILE = "accounts.dat";
    static final String TX_FILE = "transactions.dat";
    static final String LOANS_FILE = "loans.dat";

    static final String ADMIN_PASSWORD = "admin123"; // change if needed
    static final double DEFAULT_SAVINGS_INTEREST = 4.0; // annual %
    static final double DEFAULT_ATM_FEE = 10.0; // per ATM withdrawal
    static final double DEFAULT_MONTHLY_MAINTENANCE_CURRENT = 50.0;
    static final double LARGE_TRANSFER_THRESHOLD = 100000.0; // flag if transfer > this
    static final double DAILY_WITHDRAWAL_LIMIT_DEFAULT = 20000.0;

    // ---------- ANSI Colors ----------
    static final String ANSI_RESET = "\u001B[0m";
    static final String ANSI_RED = "\u001B[31m";
    static final String ANSI_GREEN = "\u001B[32m";
    static final String ANSI_YELLOW = "\u001B[33m";
    static final String ANSI_CYAN = "\u001B[36m";

    // ---------- Models ----------
    enum AccountType { SAVINGS, CURRENT }
    enum AccountStatus { ACTIVE, FROZEN, CLOSED, LOCKED }

    static class Card implements Serializable {
        private static final long serialVersionUID = 1L;
        String cardNumber; // 16 digits
        String cvv; // 3 digits
        String expiry; // MM/yy

        public Card(String cardNumber, String cvv, String expiry) {
            this.cardNumber = cardNumber;
            this.cvv = cvv;
            this.expiry = expiry;
        }
    }

    static class Account implements Serializable {
        private static final long serialVersionUID = 1L;
        String accountNo;
        String holderName;
        String pinHash;
        AccountType type;
        double balance;
        double annualInterestRate;
        Date createdAt;
        AccountStatus status;
        Card card;

        // security & tracking
        int failedPinAttempts = 0;
        LocalDate lastFailedAttemptDate = null;
        LocalDate lastWithdrawalDate = null;
        double withdrawnToday = 0.0;
        double dailyWithdrawalLimit = DAILY_WITHDRAWAL_LIMIT_DEFAULT;

        // flags for fraud
        boolean flaggedFraud = false;
        String flagReason = "";

        public Account(String accountNo, String holderName, String pinHash, AccountType type, double initialDeposit, double apr) {
            this.accountNo = accountNo;
            this.holderName = holderName;
            this.pinHash = pinHash;
            this.type = type;
            this.balance = initialDeposit;
            this.annualInterestRate = apr;
            this.createdAt = new Date();
            this.status = AccountStatus.ACTIVE;
            this.card = generateCard();
        }

        private Card generateCard() {
            String cardNo = "";
            Random r = new Random();
            for (int i = 0; i < 16; i++) cardNo += r.nextInt(10);
            String cvv = String.format("%03d", r.nextInt(1000));
            YearMonth exp = YearMonth.now().plusYears(4);
            return new Card(cardNo, cvv, exp.format(DateTimeFormatter.ofPattern("MM/yy")));
        }

        public boolean isLocked() {
            return status == AccountStatus.LOCKED;
        }
    }

    static class Transaction implements Serializable {
        private static final long serialVersionUID = 1L;
        String txId;
        String accountNo;
        Date timestamp;
        String type; // DEPOSIT, WITHDRAW, TRANSFER, INTEREST, FEE, LOAN_DISBURSE, LOAN_REPAY, LOAN_EMI
        double amount;
        String desc;
        double postBalance;

        public Transaction(String accountNo, String type, double amount, String desc, double postBalance) {
            this.txId = UUID.randomUUID().toString();
            this.accountNo = accountNo;
            this.timestamp = new Date();
            this.type = type;
            this.amount = amount;
            this.desc = desc;
            this.postBalance = postBalance;
        }
    }

    static class Loan implements Serializable {
        private static final long serialVersionUID = 1L;
        String loanId;
        String accountNo;
        String loanType; // personal/home/education
        double principal;
        double annualInterestRate;
        int tenureMonths;
        double outstanding;
        Date issuedAt;
        boolean active;
        List<String> emiHistory = new ArrayList<>(); // store txIds for EMI payments

        public Loan(String accountNo, String loanType, double principal, double annualInterestRate, int months) {
            this.loanId = UUID.randomUUID().toString();
            this.accountNo = accountNo;
            this.loanType = loanType;
            this.principal = principal;
            this.annualInterestRate = annualInterestRate;
            this.tenureMonths = months;
            this.outstanding = principal;
            this.issuedAt = new Date();
            this.active = true;
        }
    }

    // ---------- In-memory stores ----------
    static Map<String, Account> accounts = new HashMap<>();
    static List<Transaction> transactions = new ArrayList<>();
    static Map<String, List<Loan>> loansByAccount = new HashMap<>();

    static Scanner sc = new Scanner(System.in);

    // ---------- Persistence ----------
    @SuppressWarnings("unchecked")
    static void loadData() {
        // accounts
        try {
            if (Files.exists(Paths.get(ACCOUNTS_FILE))) {
                try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(ACCOUNTS_FILE))) {
                    Object obj = ois.readObject();
                    if (obj instanceof List) {
                        List<Account> list = (List<Account>) obj;
                        accounts.clear();
                        for (Account a : list) accounts.put(a.accountNo, a);
                    }
                }
            }
        } catch (Exception e) {
            printlnWarn("Could not load accounts: " + e.getMessage());
        }
        // transactions
        try {
            if (Files.exists(Paths.get(TX_FILE))) {
                try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(TX_FILE))) {
                    Object obj = ois.readObject();
                    if (obj instanceof List) transactions = (List<Transaction>) obj;
                }
            }
        } catch (Exception e) {
            printlnWarn("Could not load transactions: " + e.getMessage());
        }
        // loans
        try {
            if (Files.exists(Paths.get(LOANS_FILE))) {
                try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(LOANS_FILE))) {
                    Object obj = ois.readObject();
                    if (obj instanceof Map) loansByAccount = (Map<String, List<Loan>>) obj;
                    else if (obj instanceof List) {
                        // legacy: list of loans -> convert to map
                        List<Loan> list = (List<Loan>) obj;
                        loansByAccount.clear();
                        for (Loan L : list) {
                            loansByAccount.computeIfAbsent(L.accountNo, k -> new ArrayList<>()).add(L);
                        }
                    }
                }
            }
        } catch (Exception e) {
            printlnWarn("Could not load loans: " + e.getMessage());
        }
    }

    static void saveData() {
        try {
            List<Account> accList = new ArrayList<>(accounts.values());
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(ACCOUNTS_FILE))) {
                oos.writeObject(accList);
            }
        } catch (Exception e) {
            printlnErr("Error saving accounts: " + e.getMessage());
        }
        try {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(TX_FILE))) {
                oos.writeObject(transactions);
            }
        } catch (Exception e) {
            printlnErr("Error saving transactions: " + e.getMessage());
        }
        try {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(LOANS_FILE))) {
                oos.writeObject(loansByAccount);
            }
        } catch (Exception e) {
            printlnErr("Error saving loans: " + e.getMessage());
        }
    }

    // ---------- Utils ----------
    static String formatDate(Date d) { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d); }
    static String generateAccountNumber() { return "ACC" + System.currentTimeMillis() + (new Random().nextInt(9000)+1000); }
    static String hashPin(String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(pin.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }

    static boolean verifyPin(Account a, String pin) {
        return a.pinHash.equals(hashPin(pin));
    }

    static void printlnInfo(String s) { System.out.println(ANSI_CYAN + s + ANSI_RESET); }
    static void printlnOk(String s) { System.out.println(ANSI_GREEN + s + ANSI_RESET); }
    static void printlnErr(String s) { System.out.println(ANSI_RED + s + ANSI_RESET); }
    static void printlnWarn(String s) { System.out.println(ANSI_YELLOW + s + ANSI_RESET); }

    // ---------- Input helpers ----------
    static int readIntSafe() {
        while (true) {
            try { String l = sc.nextLine().trim(); return Integer.parseInt(l); }
            catch (Exception e) { System.out.print("Enter integer: "); }
        }
    }
    static double readDoubleSafe() {
        while (true) {
            try { String l = sc.nextLine().trim(); return Double.parseDouble(l); }
            catch (Exception e) { System.out.print("Enter number: "); }
        }
    }
    static String readPinSafe() {
        while (true) {
            String p = sc.nextLine().trim();
            if (p.matches("\\d{4}")) return p;
            System.out.print("PIN must be 4 digits: ");
        }
    }

    // ---------- Core operations ----------
    static void createAccount() {
        printlnInfo("\n--- Create Account ---");
        System.out.print("Holder Name: "); String name = sc.nextLine().trim();
        System.out.println("Type: 1. SAVINGS  2. CURRENT"); System.out.print("Choice: ");
        AccountType type = (readIntSafe() == 1) ? AccountType.SAVINGS : AccountType.CURRENT;
        System.out.print("Set 4-digit PIN: "); String pin = readPinSafe();
        System.out.print("Initial deposit: "); double init = readDoubleSafe();
        double apr = (type == AccountType.SAVINGS) ? DEFAULT_SAVINGS_INTEREST : 0.0;
        String accNo = generateAccountNumber();
        Account acc = new Account(accNo, name, hashPin(pin), type, init, apr);
        accounts.put(accNo, acc);
        transactions.add(new Transaction(accNo, "DEPOSIT", init, "Initial deposit", acc.balance));
        saveData();
        printlnOk("Account created! Account No: " + accNo);
        printlnInfo("Card: " + acc.card.cardNumber + " CVV:" + acc.card.cvv + " Exp:" + acc.card.expiry);
    }

    static void loginMenu() {
        printlnInfo("\n--- Login ---");
        System.out.print("Account No: "); String accNo = sc.nextLine().trim();
        Account acc = accounts.get(accNo);
        if (acc == null) { printlnErr("Account not found."); return; }
        if (acc.status == AccountStatus.CLOSED) { printlnErr("Account is closed."); return; }
        if (acc.status == AccountStatus.FROZEN) { printlnWarn("Account is frozen. Contact admin."); /* still allow? */ }
        if (acc.isLocked()) { printlnWarn("Account locked due to failed PIN attempts. Contact admin."); return; }

        System.out.print("Enter PIN: "); String pin = readPinSafe();
        if (!verifyPin(acc, pin)) {
            acc.failedPinAttempts++;
            acc.lastFailedAttemptDate = LocalDate.now();
            if (acc.failedPinAttempts >= 3) {
                acc.status = AccountStatus.LOCKED;
                printlnErr("3 failed attempts — account locked. Contact admin.");
            } else {
                printlnErr("Invalid PIN. Attempts: " + acc.failedPinAttempts + "/3");
            }
            saveData();
            return;
        }
        // reset failed attempts on success
        acc.failedPinAttempts = 0;
        printlnOk("Welcome, " + acc.holderName + " (" + acc.accountNo + ")");
        userSession(acc);
    }

    static void userSession(Account acc) {
        while (true) {
            System.out.println("\n--- Account Menu (" + acc.accountNo + ") ---");
            System.out.println("1. Deposit");
            System.out.println("2. Withdraw (ATM)");
            System.out.println("3. Transfer to another account");
            System.out.println("4. Check Balance");
            System.out.println("5. Mini Statement (last 5)");
            System.out.println("6. Full Transactions (paginated)");
            System.out.println("7. Apply Loan");
            System.out.println("8. View Loans");
            System.out.println("9. Repay Loan / Prepayment");
            System.out.println("10. Change PIN");
            System.out.println("11. Export Statement (csv / txt)");
            System.out.println("12. Show Card Info");
            System.out.println("0. Logout");
            System.out.print("Choice: ");
            int ch = readIntSafe();
            switch (ch) {
                case 1 -> depositFlow(acc);
                case 2 -> withdrawFlow(acc);
                case 3 -> transferFlow(acc);
                case 4 -> printlnInfo(String.format("Balance: ₹%.2f", acc.balance));
                case 5 -> miniStatement(acc);
                case 6 -> paginateTransactions(acc);
                case 7 -> applyLoanFlow(acc);
                case 8 -> viewLoans(acc);
                case 9 -> repayLoanFlow(acc);
                case 10 -> changePinFlow(acc);
                case 11 -> exportStatementFlow(acc);
                case 12 -> {
                    printlnInfo("Card Number: " + acc.card.cardNumber);
                    printlnInfo("CVV: " + acc.card.cvv + " Expiry: " + acc.card.expiry);
                }
                case 0 -> { saveData(); printlnInfo("Logged out."); return; }
                default -> printlnWarn("Invalid choice.");
            }
        }
    }

    static void depositFlow(Account acc) {
        System.out.print("Amount to deposit: "); double amt = readDoubleSafe();
        if (amt <= 0) { printlnErr("Invalid amount."); return; }
        System.out.print("Confirm PIN: "); String pin = readPinSafe();
        if (!verifyPin(acc, pin)) { printlnErr("PIN mismatch."); return; }
        acc.balance += amt;
        transactions.add(new Transaction(acc.accountNo, "DEPOSIT", amt, "Cash deposit", acc.balance));
        saveData();
        printlnOk(String.format("Deposited ₹%.2f. New balance: ₹%.2f", amt, acc.balance));
    }

    static void withdrawFlow(Account acc) {
        System.out.print("Amount to withdraw: "); double amt = readDoubleSafe();
        if (amt <= 0) { printlnErr("Invalid amount."); return; }
        // reset daily withdrawal if day changed
        LocalDate today = LocalDate.now();
        if (acc.lastWithdrawalDate == null || !acc.lastWithdrawalDate.equals(today)) {
            acc.withdrawnToday = 0.0;
            acc.lastWithdrawalDate = today;
        }
        if (acc.withdrawnToday + amt > acc.dailyWithdrawalLimit) {
            printlnErr("Daily withdrawal limit exceeded. Allowed: ₹" + acc.dailyWithdrawalLimit);
            return;
        }
        if (amt + DEFAULT_ATM_FEE > acc.balance) { printlnErr("Insufficient funds (including ATM fee)."); return; }
        // OTP for amount > threshold
        if (amt > DEFAULT_ATM_FEE * 100) { // example: large withdrawal require OTP
            if (!otpVerify(acc)) { printlnErr("OTP failed."); return; }
        }
        System.out.print("Enter PIN: "); String pin = readPinSafe();
        if (!verifyPin(acc, pin)) { printlnErr("PIN mismatch."); return; }
        acc.balance -= (amt + DEFAULT_ATM_FEE);
        acc.withdrawnToday += amt;
        transactions.add(new Transaction(acc.accountNo, "WITHDRAW", amt, "ATM withdrawal (fee ₹" + DEFAULT_ATM_FEE + ")", acc.balance));
        transactions.add(new Transaction(acc.accountNo, "FEE", DEFAULT_ATM_FEE, "ATM fee", acc.balance));
        // fraud detection
        if (amt > (LARGE_TRANSFER_THRESHOLD/2)) {
            acc.flaggedFraud = true;
            acc.flagReason = "Large withdrawal ₹" + amt;
            printlnWarn("Account flagged for review due to large withdrawal.");
        }
        saveData();
        printlnOk(String.format("Withdrew ₹%.2f (fee ₹%.2f). New balance: ₹%.2f", amt, DEFAULT_ATM_FEE, acc.balance));
    }

    static boolean otpVerify(Account acc) {
        String otp = String.valueOf(100000 + new Random().nextInt(900000));
        printlnInfo("OTP (demo) sent: " + otp);
        System.out.print("Enter OTP: ");
        String in = sc.nextLine().trim();
        return in.equals(otp);
    }

    static void transferFlow(Account acc) {
        System.out.print("Destination Account No: "); String to = sc.nextLine().trim();
        Account dst = accounts.get(to);
        if (dst == null) { printlnErr("Destination not found."); return; }
        if (dst.accountNo.equals(acc.accountNo)) { printlnErr("Cannot transfer to same account."); return; }
        System.out.print("Amount to transfer: "); double amt = readDoubleSafe();
        if (amt <= 0) { printlnErr("Invalid amount."); return; }
        if (amt > acc.balance) { printlnErr("Insufficient funds."); return; }
        // suspicious check
        if (amt > LARGE_TRANSFER_THRESHOLD) {
            acc.flaggedFraud = true; acc.flagReason = "Large transfer ₹"+amt;
            printlnWarn("Transfer flagged as large and account marked for review.");
        }
        System.out.print("Enter PIN: "); String pin = readPinSafe();
        if (!verifyPin(acc, pin)) { printlnErr("PIN mismatch."); return; }
        // OTP for large transfer
        if (amt > 50000) {
            if (!otpVerify(acc)) { printlnErr("OTP failed."); return; }
        }
        acc.balance -= amt;
        dst.balance += amt;
        transactions.add(new Transaction(acc.accountNo, "TRANSFER", amt, "Transfer to " + dst.accountNo, acc.balance));
        transactions.add(new Transaction(dst.accountNo, "TRANSFER", amt, "Transfer from " + acc.accountNo, dst.balance));
        saveData();
        printlnOk(String.format("Transferred ₹%.2f to %s. New balance: ₹%.2f", amt, dst.accountNo, acc.balance));
    }

    static void miniStatement(Account acc) {
        printlnInfo("\n--- Mini Statement (last 5) ---");
        List<Transaction> list = transactions.stream()
                .filter(t -> t.accountNo.equals(acc.accountNo))
                .collect(Collectors.toList());
        if (list.isEmpty()) { printlnInfo("No transactions."); return; }
        int start = Math.max(0, list.size() - 5);
        List<Transaction> last = list.subList(start, list.size());
        for (Transaction t : last) {
            System.out.printf("%s | %s | %s | ₹%.2f | Bal: ₹%.2f\n   %s\n", formatDate(t.timestamp), t.txId.substring(0,8), t.type, t.amount, t.postBalance, t.desc);
        }
    }

    static void paginateTransactions(Account acc) {
        List<Transaction> list = transactions.stream()
                .filter(t -> t.accountNo.equals(acc.accountNo))
                .collect(Collectors.toList());
        if (list.isEmpty()) { printlnInfo("No transactions."); return; }
        int page = 0; int pageSize = 10;
        while (true) {
            int from = page * pageSize; if (from >= list.size()) { printlnWarn("No more pages."); break; }
            int to = Math.min(list.size(), from + pageSize);
            printlnInfo(String.format("Showing %d - %d of %d", from+1, to, list.size()));
            for (int i = from; i < to; i++) {
                Transaction t = list.get(i);
                System.out.printf("%d) %s | %s | %s | ₹%.2f | Bal: ₹%.2f\n   %s\n", i+1, formatDate(t.timestamp), t.txId.substring(0,8), t.type, t.amount, t.postBalance, t.desc);
            }
            System.out.println("Commands: n (next), p (prev), q (quit)");
            String cmd = sc.nextLine().trim();
            if (cmd.equalsIgnoreCase("n")) page++;
            else if (cmd.equalsIgnoreCase("p") && page>0) page--;
            else break;
        }
    }

    static void applyLoanFlow(Account acc) {
        printlnInfo("\n--- Apply Loan ---");
        System.out.print("Loan type (personal/home/education): "); String type = sc.nextLine().trim();
        System.out.print("Principal amount: "); double p = readDoubleSafe();
        System.out.print("Annual rate (e.g., 10.5): "); double r = readDoubleSafe();
        System.out.print("Tenure months: "); int months = readIntSafe();
        Loan L = new Loan(acc.accountNo, type, p, r, months);
        loansByAccount.computeIfAbsent(acc.accountNo, k -> new ArrayList<>()).add(L);
        acc.balance += p;
        transactions.add(new Transaction(acc.accountNo, "LOAN_DISBURSE", p, "Loan disbursed ID: "+L.loanId, acc.balance));
        saveData();
        double emi = calculateEMI(p, r, months);
        printlnOk(String.format("Loan %s disbursed. EMI: ₹%.2f for %d months. LoanID: %s", type, emi, months, L.loanId));
    }

    static double calculateEMI(double principal, double annualRatePercent, int months) {
        double r = annualRatePercent / 12.0 / 100.0;
        if (r == 0) return principal/months;
        double emi = (principal * r * Math.pow(1+r, months)) / (Math.pow(1+r, months) - 1);
        return emi;
    }

    static void viewLoans(Account acc) {
        List<Loan> list = loansByAccount.getOrDefault(acc.accountNo, Collections.emptyList());
        if (list.isEmpty()) { printlnInfo("No loans."); return; }
        for (Loan L : list) {
            System.out.printf("LoanID: %s | Type: %s | Principal: ₹%.2f | Rate: %.2f%% | Tenure: %d mo | Outstanding: ₹%.2f | Active: %s%n",
                    L.loanId, L.loanType, L.principal, L.annualInterestRate, L.tenureMonths, L.outstanding, L.active);
        }
    }

    static void repayLoanFlow(Account acc) {
        List<Loan> list = loansByAccount.getOrDefault(acc.accountNo, Collections.emptyList());
        if (list.isEmpty()) { printlnInfo("No loans."); return; }
        viewLoans(acc);
        System.out.print("Enter LoanID to repay: "); String lid = sc.nextLine().trim();
        Loan L = null;
        for (Loan x : list) if (x.loanId.equals(lid)) { L = x; break; }
        if (L == null) { printlnErr("Loan not found."); return; }
        if (!L.active) { printlnInfo("Loan already closed."); return; }
        System.out.println("Choose: 1) Pay EMI  2) Prepay (custom amount)");
        int ch = readIntSafe();
        if (ch == 1) {
            double emi = calculateEMI(L.principal, L.annualInterestRate, L.tenureMonths);
            double monthlyRate = L.annualInterestRate / 12.0 / 100.0;
            double interestPart = L.outstanding * monthlyRate;
            double principalPart = emi - interestPart;
            if (principalPart < 0) principalPart = 0;
            if (emi > acc.balance) { printlnErr("Insufficient funds to pay EMI."); return; }
            acc.balance -= emi;
            L.outstanding -= principalPart;
            L.emiHistory.add("EMI:" + emi + ":" + new Date().getTime());
            transactions.add(new Transaction(acc.accountNo, "LOAN_EMI", emi, "Loan EMI payment ID: " + L.loanId + " (Principal:" + principalPart + " Interest:" + interestPart + ")", acc.balance));
            if (L.outstanding <= 0.01) { L.outstanding = 0; L.active = false; printlnOk("Loan cleared."); }
            saveData();
            printlnOk(String.format("EMI paid. Principal: ₹%.2f Interest: ₹%.2f Remaining outstanding: ₹%.2f", principalPart, interestPart, L.outstanding));
        } else {
            System.out.print("Enter amount to prepay: "); double amt = readDoubleSafe();
            if (amt > acc.balance) { printlnErr("Insufficient balance."); return; }
            // optional penalty logic: simple example 1% penalty on prepayment if outstanding > threshold
            double penalty = 0;
            if (amt > L.outstanding) amt = L.outstanding; // can't pay more than outstanding
            // ask user whether to apply penalty rules (for demo no penalty)
            acc.balance -= amt;
            L.outstanding -= amt;
            transactions.add(new Transaction(acc.accountNo, "LOAN_REPAY", amt, "Loan prepayment ID: " + L.loanId + " (penalty ₹" + penalty + ")", acc.balance));
            if (L.outstanding <= 0.01) { L.outstanding = 0; L.active = false; printlnOk("Loan cleared."); }
            saveData();
            printlnOk(String.format("Prepayment successful. Remaining outstanding: ₹%.2f", L.outstanding));
        }
    }

    static void changePinFlow(Account acc) {
        System.out.print("Enter current PIN: "); String cur = readPinSafe();
        if (!verifyPin(acc, cur)) { printlnErr("Wrong PIN."); return; }
        System.out.print("Enter new PIN: "); String np = readPinSafe();
        System.out.print("Confirm new PIN: "); String cp = readPinSafe();
        if (!np.equals(cp)) { printlnErr("PINs do not match."); return; }
        acc.pinHash = hashPin(np);
        saveData();
        printlnOk("PIN changed successfully.");
    }

    static void exportStatementFlow(Account acc) {
        printlnInfo("Export: 1) CSV full  2) TXT summary");
        int ch = readIntSafe();
        List<Transaction> list = transactions.stream().filter(t -> t.accountNo.equals(acc.accountNo)).collect(Collectors.toList());
        if (list.isEmpty()) { printlnWarn("No transactions to export."); return; }
        if (ch == 1) {
            String fname = String.format("statement_%s_%s.csv", acc.accountNo, LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM")));
            try (PrintWriter pw = new PrintWriter(new FileWriter(fname))) {
                pw.println("timestamp,txid,type,amount,postBalance,desc");
                for (Transaction t : list) {
                    pw.printf("%s,%s,%s,%.2f,%.2f,\"%s\"%n", formatDate(t.timestamp), t.txId, t.type, t.amount, t.postBalance, t.desc.replace("\"", "\"\""));
                }
                printlnOk("Exported CSV: " + fname);
            } catch (Exception e) { printlnErr("Error exporting: " + e.getMessage()); }
        } else {
            String fname = String.format("statement_%s_%s.txt", acc.accountNo, LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM")));
            try (PrintWriter pw = new PrintWriter(new FileWriter(fname))) {
                pw.println("Statement for Account " + acc.accountNo);
                for (Transaction t : list) {
                    pw.printf("%s | %s | %s | ₹%.2f | Bal: ₹%.2f | %s%n", formatDate(t.timestamp), t.txId.substring(0,8), t.type, t.amount, t.postBalance, t.desc);
                }
                printlnOk("Exported TXT: " + fname);
            } catch (Exception e) { printlnErr("Error exporting: " + e.getMessage()); }
        }
    }

    // ---------- Admin ----------
    static void adminMenu() {
        System.out.print("Enter admin password: "); String pw = sc.nextLine().trim();
        if (!pw.equals(ADMIN_PASSWORD)) { printlnErr("Wrong password."); return; }
        printlnOk("Welcome, admin.");
        while (true) {
            System.out.println("\n--- Admin Panel ---");
            System.out.println("1. List All Accounts");
            System.out.println("2. View All Transactions");
            System.out.println("3. View All Loans");
            System.out.println("4. Freeze / Unfreeze Account");
            System.out.println("5. Close Account");
            System.out.println("6. Change Global Interest Rate (Savings)");
            System.out.println("7. Apply Monthly Interest to all savings (manual)");
            System.out.println("8. Apply Monthly Maintenance Fee to current accounts");
            System.out.println("9. Generate Monthly Statements for all accounts");
            System.out.println("0. Exit Admin");
            System.out.print("Choice: "); int ch = readIntSafe();
            switch (ch) {
                case 1 -> { for (Account a : accounts.values()) System.out.printf("%s | %s | %s | %s | ₹%.2f%n", a.accountNo, a.holderName, a.type, a.status, a.balance); }
                case 2 -> {
                    for (Transaction t : transactions) System.out.printf("%s | %s | %s | ₹%.2f | %s%n", formatDate(t.timestamp), t.accountNo, t.type, t.amount, t.desc);
                }
                case 3 -> {
                    loansByAccount.values().stream().flatMap(List::stream).forEach(l -> System.out.printf("%s | %s | ₹%.2f | out: ₹%.2f | active:%s%n", l.loanId, l.accountNo, l.principal, l.outstanding, l.active));
                }
                case 4 -> {
                    System.out.print("Account No: "); String an = sc.nextLine().trim();
                    Account a = accounts.get(an); if (a==null) { printlnErr("Not found"); break; }
                    System.out.println("1.Freeze 2.Unfreeze"); int x = readIntSafe();
                    if (x==1) { a.status = AccountStatus.FROZEN; printlnOk("Frozen."); } else { a.status = AccountStatus.ACTIVE; printlnOk("Unfrozen."); }
                    saveData();
                }
                case 5 -> {
                    System.out.print("Account No to close: "); String an = sc.nextLine().trim();
                    Account a = accounts.get(an); if (a==null) { printlnErr("Not found"); break; }
                    a.status = AccountStatus.CLOSED; printlnOk("Account closed."); saveData();
                }
                case 6 -> {
                    System.out.print("New annual interest% for savings: "); double r = readDoubleSafe();
                    for (Account a : accounts.values()) if (a.type == AccountType.SAVINGS) a.annualInterestRate = r;
                    printlnOk("Updated savings interest to " + r + "% for all savings accounts."); saveData();
                }
                case 7 -> { applyInterestToAllSavings(); saveData(); printlnOk("Applied monthly interest to all savings."); }
                case 8 -> { applyMonthlyMaintenanceToCurrent(); saveData(); printlnOk("Applied maintenance fee to current accounts."); }
                case 9 -> { generateMonthlyStatementsAll(); printlnOk("Generated monthly statements for all accounts."); }
                case 0 -> { saveData(); printlnInfo("Exiting admin."); return; }
                default -> printlnWarn("Invalid.");
            }
        }
    }

    static void applyInterestToAllSavings() {
        for (Account a : accounts.values()) {
            if (a.type == AccountType.SAVINGS && a.status==AccountStatus.ACTIVE) {
                double monthlyRate = a.annualInterestRate / 12.0 / 100.0;
                double interest = a.balance * monthlyRate;
                a.balance += interest;
                transactions.add(new Transaction(a.accountNo, "INTEREST", interest, "Monthly interest", a.balance));
            }
        }
    }

    static void applyMonthlyMaintenanceToCurrent() {
        for (Account a : accounts.values()) {
            if (a.type == AccountType.CURRENT && a.status==AccountStatus.ACTIVE) {
                a.balance -= DEFAULT_MONTHLY_MAINTENANCE_CURRENT;
                transactions.add(new Transaction(a.accountNo, "FEE", DEFAULT_MONTHLY_MAINTENANCE_CURRENT, "Monthly maintenance", a.balance));
            }
        }
    }

    static void generateMonthlyStatementsAll() {
        for (Account a : accounts.values()) {
            List<Transaction> list = transactions.stream().filter(t -> t.accountNo.equals(a.accountNo)).collect(Collectors.toList());
            if (list.isEmpty()) continue;
            String fname = String.format("statement_%s_%s.csv", a.accountNo, LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM")));
            try (PrintWriter pw = new PrintWriter(new FileWriter(fname))) {
                pw.println("timestamp,txid,type,amount,postBalance,desc");
                for (Transaction t : list) pw.printf("%s,%s,%s,%.2f,%.2f,\"%s\"%n", formatDate(t.timestamp), t.txId, t.type, t.amount, t.postBalance, t.desc.replace("\"","\"\""));
            } catch (Exception e) { printlnErr("Failed statement for " + a.accountNo + ": " + e.getMessage()); }
        }
    }

    // ---------- Fraud detection & credit score ----------
    static void runBasicFraudChecks() {
        for (Account a : accounts.values()) {
            long recentTransfers = transactions.stream().filter(t -> t.accountNo.equals(a.accountNo) && t.type.equals("TRANSFER") && (System.currentTimeMillis()-t.timestamp.getTime()) < (1000L*60*60*24)).count();
            if (recentTransfers > 10) { a.flaggedFraud = true; a.flagReason = "Many transfers today"; }
        }
    }

    static int computeCreditScore(String accountNo) {
        Account a = accounts.get(accountNo); if (a == null) return 0;
        int score = 50;
        // age
        long ageDays = (new Date().getTime() - a.createdAt.getTime()) / (1000L*60*60*24);
        if (ageDays > 365) score += 10;
        if (ageDays > 365*2) score += 5;
        // loan repayment: reward if many EMI payments exist and few defaults
        List<Loan> Ls = loansByAccount.getOrDefault(accountNo, Collections.emptyList());
        for (Loan L : Ls) {
            score -= (int)(L.outstanding / (L.principal+1) * 10); // heuristic
            score += Math.min(5, L.emiHistory.size());
        }
        // transaction activity
        long deposits = transactions.stream().filter(t -> t.accountNo.equals(accountNo) && t.type.equals("DEPOSIT")).count();
        if (deposits > 12) score += 10;
        if (a.flaggedFraud) score -= 20;
        return Math.max(0, Math.min(100, score));
    }

    // ---------- Main ----------
    public static void main(String[] args) {
        printlnInfo("=== Enhanced Console Bank System ===");
        loadData();
        runBasicFraudChecks();
        while (true) {
            System.out.println("\nMain Menu:");
            System.out.println("1. Create Account");
            System.out.println("2. Login");
            System.out.println("3. Admin Panel");
            System.out.println("4. Compute Credit Score for an account (admin)");
            System.out.println("0. Exit");
            System.out.print("Choice: ");
            int c = readIntSafe();
            switch (c) {
                case 1 -> createAccount();
                case 2 -> loginMenu();
                case 3 -> adminMenu();
                case 4 -> {
                    System.out.print("Account No: "); String an = sc.nextLine().trim();
                    int s = computeCreditScore(an);
                    printlnInfo("Credit score for " + an + " is " + s + "/100");
                }
                case 0 -> { saveData(); printlnInfo("Bye!"); System.exit(0); }
                default -> printlnWarn("Invalid.");
            }
        }
    }
}
