package vn.iotstar.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    String uploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request, Model model) {
        model.addAttribute("status", 413);
        model.addAttribute("message", "Tệp tải lên vượt giới hạn 25 MB hoặc tổng dung lượng vượt 130 MB.");
        model.addAttribute("path", request.getRequestURI());
        return "error";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String badRequest(IllegalArgumentException ex, HttpServletRequest request, Model model) {
        model.addAttribute("status", 400);
        model.addAttribute("message", ex.getMessage());
        model.addAttribute("path", request.getRequestURI());
        return "error";
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    String denied(AccessDeniedException ex, HttpServletRequest request, Model model) {
        model.addAttribute("status", 403);
        model.addAttribute("message", "Bạn không có quyền truy cập chức năng này.");
        model.addAttribute("path", request.getRequestURI());
        return "error";
    }
}
