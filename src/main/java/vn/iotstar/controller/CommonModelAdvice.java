package vn.iotstar.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import vn.iotstar.entity.User;
import vn.iotstar.service.CurrentUserService;

@ControllerAdvice(assignableTypes = AuthController.class)
@RequiredArgsConstructor
public class CommonModelAdvice {
    private final CurrentUserService currentUserService;

    @ModelAttribute("currentUser")
    User currentUser() {
        return currentUserService.optional().orElse(null);
    }
}
