package vn.iotstar.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.dto.WebForms.AddressForm;
import vn.iotstar.dto.WebForms.BankForm;
import vn.iotstar.dto.WebForms.ProfileForm;
import vn.iotstar.entity.Address;
import vn.iotstar.entity.User;
import vn.iotstar.repository.AddressRepository;
import vn.iotstar.repository.UserRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileService {
    private final AddressRepository addressRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final MediaStorageService mediaStorageService;
    private final MediaValidationService mediaValidationService;

    @Transactional
    public void updateProfile(User user, ProfileForm form) {
        user = lockUser(user);
        user.setFullName(form.getFullName().trim());
        user.setPhone(blankToNull(form.getPhone()));
        userRepository.save(user);
    }

    @Transactional
    public void updateBank(User user, BankForm form) {
        user = lockUser(user);
        user.setBankName(blankToNull(form.getBankName()));
        user.setBankAccountName(blankToNull(form.getAccountName()));
        user.setBankAccountNumber(blankToNull(form.getAccountNumber()));
        userRepository.save(user);
    }

    @Transactional
    public void changePassword(User user, String currentPassword, String newPassword) {
        user = lockUser(user);
        if (newPassword == null || newPassword.length() < 8 || newPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Mật khẩu mới phải có ít nhất 8 ký tự và tối đa 72 byte UTF-8.");
        }
        if (currentPassword == null || currentPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Mật khẩu hiện tại không chính xác.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Mật khẩu mới phải khác mật khẩu hiện tại.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    @Transactional
    public void updateAvatar(User user, MultipartFile avatar) {
        user = lockUser(user);
        if (avatar == null || avatar.isEmpty()) throw new IllegalArgumentException("Vui lòng chọn ảnh đại diện.");
        mediaValidationService.validate(avatar, "avatars");
        user.setAvatarUrl(mediaStorageService.upload(avatar, "avatars"));
        userRepository.save(user);
    }

    @Transactional
    public void removeAvatar(User user) {
        user = lockUser(user);
        user.setAvatarUrl(null);
        userRepository.save(user);
    }

    public List<Address> addresses(User user) {
        return addressRepository.findByUserIdOrderByDefaultAddressDescIdAsc(user.getId());
    }

    public Address addressForEdit(User user, Long id) {
        return addressRepository.findByIdAndUserId(id, user.getId())
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy địa chỉ."));
    }

    @Transactional
    public void saveAddress(User user, Long id, AddressForm form) {
        user = lockUser(user);
        Address address = id == null ? new Address() : addressRepository.findByIdAndUserId(id, user.getId())
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy địa chỉ."));
        address.setUser(user);
        address.setReceiverName(form.getReceiverName().trim());
        address.setPhone(form.getPhone().trim());
        address.setProvince(form.getProvince().trim());
        address.setDistrict(form.getDistrict().trim());
        address.setWard(form.getWard().trim());
        address.setDetail(form.getDetail().trim());
        boolean makeDefault = form.isDefaultAddress() || address.isDefaultAddress() || addressRepository.countByUserId(user.getId()) == 0;
        if (makeDefault) {
            addressRepository.findByUserIdOrderByDefaultAddressDescIdAsc(user.getId()).forEach(other -> other.setDefaultAddress(false));
        }
        address.setDefaultAddress(makeDefault);
        addressRepository.save(address);
    }

    @Transactional
    public void makeDefault(User user, Long id) {
        user = lockUser(user);
        Address selected = addressRepository.findByIdAndUserId(id, user.getId())
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy địa chỉ."));
        addressRepository.findByUserIdOrderByDefaultAddressDescIdAsc(user.getId())
            .forEach(address -> address.setDefaultAddress(address.getId().equals(selected.getId())));
    }

    @Transactional
    public void deleteAddress(User user, Long id) {
        user = lockUser(user);
        Address address = addressRepository.findByIdAndUserId(id, user.getId())
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy địa chỉ."));
        boolean wasDefault = address.isDefaultAddress();
        addressRepository.delete(address);
        addressRepository.flush();
        if (wasDefault) {
            addressRepository.findByUserIdOrderByDefaultAddressDescIdAsc(user.getId()).stream().findFirst()
                .ifPresent(first -> first.setDefaultAddress(true));
        }
    }

    private User lockUser(User user) {
        return userRepository.findByIdForUpdate(user.getId()).orElseThrow(() ->
            new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
