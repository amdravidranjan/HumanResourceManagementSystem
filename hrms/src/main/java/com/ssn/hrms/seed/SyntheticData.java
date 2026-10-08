package com.ssn.hrms.seed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.ssn.hrms.employee.Employee;

/** Deterministic generator of fictitious employee data. No real personal data is used anywhere. */
public class SyntheticData {

    static final String[] FIRST = {"Aarav", "Aditi", "Akash", "Ananya", "Anil", "Anitha", "Arjun", "Arun", "Bhavana", "Chitra",
        "Deepak", "Deepika", "Dinesh", "Divya", "Ganesh", "Gayathri", "Gokul", "Harini", "Hari", "Indira", "Ishaan", "Janani",
        "Jayanth", "Kavya", "Karthik", "Keerthana", "Kiran", "Lakshmi", "Lokesh", "Madhavi", "Mahesh", "Meena", "Mohan", "Nandini",
        "Naveen", "Nisha", "Nithin", "Pooja", "Pradeep", "Prakash", "Priya", "Rahul", "Rajesh", "Ramya", "Ravi", "Revathi",
        "Rohit", "Sabari", "Sandhya", "Sanjay", "Saranya", "Sathish", "Shalini", "Shankar", "Shreya", "Siddharth", "Sneha",
        "Sowmya", "Srinivas", "Subha", "Suresh", "Swathi", "Tarun", "Uma", "Varun", "Vasanth", "Vidya", "Vignesh", "Vijay",
        "Vinitha", "Yamini", "Yash", "Abinaya", "Balaji", "Charan", "Dharani", "Elango", "Fathima", "Gowtham", "Hemanth",
        "Ilakkiya", "Jeevan", "Kamala", "Lavanya", "Manoj", "Nirmala", "Oviya", "Pavithra", "Raghav", "Sangeetha", "Thilak",
        "Usha", "Vasudha", "Yogesh", "Zara", "Aishwarya", "Bharath", "Dhanush", "Ezhil", "Geetha", "Hamsa", "Iniya", "Jothi",
        "Kishore", "Latha", "Malar", "Nakul", "Padma", "Rekha", "Selvi", "Tamil", "Uday", "Vani", "Kavin", "Mithra", "Nila",
        "Pranav", "Ritika", "Sakthi"};
    static final String[] LAST = {"Iyer", "Iyengar", "Raman", "Krishnan", "Subramanian", "Venkatesh", "Natarajan", "Sundaram",
        "Rajan", "Pillai", "Nair", "Menon", "Reddy", "Rao", "Naidu", "Sharma", "Verma", "Gupta", "Patel", "Shah", "Mehta",
        "Joshi", "Kulkarni", "Deshpande", "Banerjee", "Chatterjee", "Mukherjee", "Das", "Bose", "Ghosh", "Singh", "Kumar",
        "Yadav", "Mishra", "Pandey", "Chaudhary", "Agarwal", "Kapoor", "Malhotra", "Khanna", "Arora", "Bhat", "Hegde", "Shetty",
        "Kamath", "Prabhu", "Murthy", "Gowda", "Ramesh", "Suresh", "Mohan", "Selvam", "Murugan", "Arumugam", "Palani",
        "Chandran", "Balan", "Varma", "Thomas", "George", "Mathew", "Joseph", "Fernandes", "DSouza", "Pereira", "Rodrigues",
        "Saxena", "Tiwari", "Srivastava", "Dubey", "Jain", "Bansal", "Goyal", "Mittal", "Sethi", "Ahuja", "Bajaj", "Chopra",
        "Dutta", "Sen"};
    static final Map<String, String[]> SKILLS = Map.of(
            "Engineering", new String[] {"Java", "Spring Boot", "Python", "React", "Kubernetes", "Docker", "AWS", "MongoDB", "Redis", "Go", "SQL", "Microservices"},
            "Finance", new String[] {"Accounting", "Taxation", "Excel", "Financial Modelling", "Audit", "SAP FICO", "Budgeting"},
            "Human Resources", new String[] {"Recruitment", "Payroll", "Employee Relations", "Compliance", "Onboarding", "HR Analytics"},
            "Sales", new String[] {"Negotiation", "CRM", "Lead Generation", "Key Accounts", "Salesforce", "Presentations"},
            "Marketing", new String[] {"SEO", "Content Writing", "Social Media", "Google Ads", "Branding", "Analytics"},
            "Operations", new String[] {"Supply Chain", "Logistics", "Vendor Management", "Six Sigma", "Process Improvement"},
            "Legal", new String[] {"Contracts", "Corporate Law", "IP Law", "Compliance", "Litigation"},
            "Customer Support", new String[] {"Customer Service", "Zendesk", "Troubleshooting", "Communication", "Ticketing"},
            "Product", new String[] {"Roadmapping", "User Research", "Agile", "Jira", "Analytics", "A/B Testing"},
            "Design", new String[] {"Figma", "UX Research", "Prototyping", "Illustrator", "Design Systems", "Accessibility"});
    static final Map<String, String[]> TITLES = Map.of(
            "Engineering", new String[] {"Software Engineer", "Senior Software Engineer", "Engineering Manager", "VP Engineering"},
            "Finance", new String[] {"Accountant", "Senior Financial Analyst", "Finance Manager", "Finance Director"},
            "Human Resources", new String[] {"HR Associate", "HR Business Partner", "HR Manager", "HR Director"},
            "Sales", new String[] {"Sales Executive", "Senior Sales Executive", "Sales Manager", "Head of Sales"},
            "Marketing", new String[] {"Marketing Associate", "Marketing Specialist", "Marketing Manager", "Head of Marketing"},
            "Operations", new String[] {"Operations Executive", "Operations Analyst", "Operations Manager", "Head of Operations"},
            "Legal", new String[] {"Legal Associate", "Legal Counsel", "Senior Counsel", "General Counsel"},
            "Customer Support", new String[] {"Support Associate", "Senior Support Associate", "Support Manager", "Head of Support"},
            "Product", new String[] {"Associate Product Manager", "Product Manager", "Senior Product Manager", "Head of Product"},
            "Design", new String[] {"UI Designer", "UX Designer", "Design Lead", "Head of Design"});

    private final Random rnd;

    public SyntheticData(Random rnd) {
        this.rnd = rnd;
    }

    public Random random() {
        return rnd;
    }

    public String firstName() {
        return FIRST[rnd.nextInt(FIRST.length)];
    }

    public String lastName() {
        return LAST[rnd.nextInt(LAST.length)];
    }

    public String fullName() {
        return firstName() + " " + lastName();
    }

    public List<String> skills(String dept, int n) {
        List<String> pool = new ArrayList<>(List.of(SKILLS.getOrDefault(dept, SKILLS.get("Operations"))));
        java.util.Collections.shuffle(pool, rnd);
        return new ArrayList<>(pool.subList(0, Math.min(n, pool.size())));
    }

    /** level 1 = individual contributor, 2 = senior, 3 = manager, 4 = department head. */
    public String designation(String dept, int level) {
        return TITLES.getOrDefault(dept, TITLES.get("Operations"))[Math.max(1, Math.min(level, 4)) - 1];
    }

    public Employee.Salary salary(int level) {
        int[][] bands = {{20_000, 30_000}, {30_000, 50_000}, {60_000, 90_000}, {100_000, 150_000}};
        int[] b = bands[Math.max(1, Math.min(level, 4)) - 1];
        Employee.Salary s = new Employee.Salary();
        s.basic = b[0] + rnd.nextInt((b[1] - b[0]) / 500 + 1) * 500;
        s.hra = Math.round(s.basic * 0.4);
        s.allowances = Math.round(s.basic * (0.2 + rnd.nextInt(11) / 100.0));
        s.deductions = 200; // professional tax
        return s;
    }

    public String phone() {
        return "9" + (100_000_000 + rnd.nextInt(899_999_999));
    }

    public String joinDate(LocalDate today) {
        return today.minusDays(60 + rnd.nextInt(3650)).toString();
    }
}
