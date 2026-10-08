package com.ssn.hrms.leave;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.config.NodeOnly;
import com.ssn.hrms.employee.Employee;

@NodeOnly
@RestController
@RequestMapping("/api/leaves")
public class LeaveController {

    public record Decision(boolean approve, String comment) {
    }

    private final LeaveService leaves;

    public LeaveController(LeaveService leaves) {
        this.leaves = leaves;
    }

    @PostMapping
    public LeaveRequest apply(@RequestAttribute("user") CurrentUser user, @RequestBody LeaveService.ApplyRequest request) {
        return leaves.apply(user, request);
    }

    @GetMapping("/me")
    public List<LeaveRequest> mine(@RequestAttribute("user") CurrentUser user) {
        return leaves.mine(user.employeeId());
    }

    @GetMapping("/balance/{employeeId}")
    public Employee.LeaveBalance balance(@RequestAttribute("user") CurrentUser user, @PathVariable String employeeId) {
        return leaves.balance(user, employeeId);
    }

    @GetMapping("/pending")
    public List<LeaveRequest> pending(@RequestAttribute("user") CurrentUser user) {
        return leaves.pending(user);
    }

    @PostMapping("/{employeeId}/{leaveId}/decision")
    public LeaveRequest decide(@RequestAttribute("user") CurrentUser user, @PathVariable String employeeId,
            @PathVariable String leaveId, @RequestBody Decision decision) {
        return leaves.decide(user, employeeId, leaveId, decision.approve(), decision.comment());
    }

    @PostMapping("/{leaveId}/cancel")
    public LeaveRequest cancel(@RequestAttribute("user") CurrentUser user, @PathVariable String leaveId) {
        return leaves.cancel(user, leaveId);
    }
}
