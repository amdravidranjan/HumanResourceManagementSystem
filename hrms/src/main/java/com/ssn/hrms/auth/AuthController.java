package com.ssn.hrms.auth;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.SessionService;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;
import com.ssn.hrms.employee.EmployeeService;

import jakarta.servlet.http.HttpServletRequest;

@NodeOnly
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(String email, String password) {
    }

    private final EmployeeService employees;
    private final SessionService sessions;
    private final BCryptPasswordEncoder encoder;

    public AuthController(EmployeeService employees, SessionService sessions, BCryptPasswordEncoder encoder) {
        this.employees = employees;
        this.sessions = sessions;
        this.encoder = encoder;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest r) {
        if (r == null || r.email() == null || r.email().isBlank() || r.password() == null) {
            throw ApiException.badRequest("Email and password are required");
        }
        Employee e = employees.findByEmail(r.email().trim().toLowerCase(Locale.ROOT));
        if (e == null || e.passwordHash == null || !encoder.matches(r.password(), e.passwordHash)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        if (!"ACTIVE".equals(e.status)) {
            throw ApiException.forbidden("This account is " + e.status.toLowerCase(Locale.ROOT));
        }
        String token = sessions.create(e.id, e.role, e.name, e.department);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("employeeId", e.id);
        out.put("name", e.name);
        out.put("role", e.role);
        out.put("department", e.department);
        return out;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        sessions.delete(SessionService.bearer(request));
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public CurrentUser me(@RequestAttribute("user") CurrentUser user) {
        return user;
    }
}
