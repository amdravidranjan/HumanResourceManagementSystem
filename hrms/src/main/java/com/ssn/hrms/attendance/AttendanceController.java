package com.ssn.hrms.attendance;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.ApiException;
import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.common.Dates;
import com.ssn.hrms.common.Guard;
import com.ssn.hrms.config.NodeOnly;

@NodeOnly
@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {

    private final AttendanceService attendance;

    public AttendanceController(AttendanceService attendance) {
        this.attendance = attendance;
    }

    @PostMapping("/check-in")
    public Attendance checkIn(@RequestAttribute("user") CurrentUser user) {
        return attendance.checkIn(user);
    }

    @PostMapping("/check-out")
    public Attendance checkOut(@RequestAttribute("user") CurrentUser user) {
        return attendance.checkOut(user);
    }

    @GetMapping("/me")
    public List<Attendance> mine(@RequestAttribute("user") CurrentUser user, @RequestParam(required = false) String month) {
        return attendance.month(user.employeeId(), Dates.month(month));
    }

    @GetMapping("/employee/{employeeId}")
    public List<Attendance> forEmployee(@RequestAttribute("user") CurrentUser user, @PathVariable String employeeId,
            @RequestParam(required = false) String month) {
        if (!user.isManager() && !user.employeeId().equals(employeeId)) {
            throw ApiException.forbidden("Only managers or HR can view others' attendance");
        }
        return attendance.month(employeeId, Dates.month(month));
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestAttribute("user") CurrentUser user, @RequestParam(required = false) String month) {
        Guard.hr(user);
        return attendance.summary(Dates.month(month));
    }
}
