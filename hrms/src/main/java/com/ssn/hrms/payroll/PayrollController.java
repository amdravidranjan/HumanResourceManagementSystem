package com.ssn.hrms.payroll;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.config.NodeOnly;

@NodeOnly
@RestController
@RequestMapping("/api/payroll")
public class PayrollController {

    private final PayrollService payroll;

    public PayrollController(PayrollService payroll) {
        this.payroll = payroll;
    }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestAttribute("user") CurrentUser user,
            @RequestBody(required = false) PayrollService.RunRequest request) {
        return payroll.run(user, request);
    }

    @GetMapping("/me")
    public List<Payslip> mine(@RequestAttribute("user") CurrentUser user) {
        return payroll.mine(user.employeeId());
    }

    @GetMapping("/{employeeId}/{month}")
    public Payslip get(@RequestAttribute("user") CurrentUser user, @PathVariable String employeeId, @PathVariable String month) {
        return payroll.get(user, employeeId, month);
    }

    @PostMapping("/{employeeId}/{month}/share")
    public Map<String, Object> share(@RequestAttribute("user") CurrentUser user, @PathVariable String employeeId,
            @PathVariable String month) {
        return payroll.share(user, employeeId, month);
    }
}
