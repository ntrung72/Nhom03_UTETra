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
public class ProfileService {
    private final AddressRepository addressRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final MediaStorageService mediaStorageService;
    private final MediaValidationService mediaValidationService;

    @Transactional
    public void updateProfile(User user, ProfileForm form) {
        user.setFullName(form.getFullName().trim());
        user.setPhone(blankToNull(form.getPhone()));
        userRepository.save(user);
    }

    @Transactional
    public void updateBank(User user, BankForm form) {
        user.setBankName(blankToNull(form.getBankName()));
        user.setBankAccountName(blankToNull(form.getAccountName()));
        user.setBankAccountNumber(blankToNull(form.getAccountNumber()));
        userRepository.save(user);
    }

    @Transactional
    public void changePassword(User user, String currentPassword, String newPassword) {
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
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
        if (avatar == null || avatar.isEmpty()) throw new IllegalArgumentException("Vui lòng chọn ảnh đại diện.");
        mediaValidationService.validate(avatar, "avatars");
        String previousUrl = user.getAvatarUrl();
        user.setAvatarUrl(mediaStorageService.upload(avatar, "avatars"));
        userRepository.save(user);
        mediaStorageService.deleteAfterCommit(previousUrl);
    }

    @Transactional
    public void removeAvatar(User user) {
        String previousUrl = user.getAvatarUrl();
        user.setAvatarUrl(null);
        userRepository.save(user);
        mediaStorageService.deleteAfterCommit(previousUrl);
    }

    public List<Address> addresses(User user) {
        return addressRepository.findByUserIdOrderByDefaultAddressDescIdAsc(user.getId());
    }

    public Address addressForEdit(User user, Long id) {
        return addressRepository.findByIdAndUserId(id, user.getId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy địa chỉ."));
    }

    @Transactional
    public void saveAddress(User user, Long id, AddressForm form) {
        Address address = id == null ? new Address() : addressRepository.findByIdAndUserId(id, user.getId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy địa chỉ."));
        address.setUser(user);
        address.setReceiverName(form.getReceiverName().trim());
        address.setPhone(form.getPhone().trim());
        address.setProvince(form.getProvince().trim());
        address.setDistrict(form.getDistrict().trim());
        address.setWard(form.getWard().trim());
        address.setDetail(form.getDetail().trim());
        boolean makeDefault = form.isDefaultAddress() || addressRepository.countByUserId(user.getId()) == 0;
        if (makeDefault) {
            addressRepository.findByUserIdOrderByDefaultAddressDescIdAsc(user.getId()).forEach(other -> other.setDefaultAddress(false));
        }
        address.setDefaultAddress(makeDefault);
        addressRepository.save(address);
    }

    @Transactional
    public void makeDefault(User user, Long id) {
        Address selected = addressRepository.findByIdAndUserId(id, user.getId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy địa chỉ."));
        addressRepository.findByUserIdOrderByDefaultAddressDescIdAsc(user.getId())
            .forEach(address -> address.setDefaultAddress(address.getId().equals(selected.getId())));
    }

    @Transactional
    public void deleteAddress(User user, Long id) {
        Address address = addressRepository.findByIdAndUserId(id, user.getId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy địa chỉ."));
        boolean wasDefault = address.isDefaultAddress();
        addressRepository.delete(address);
        if (wasDefault) {
            addressRepository.findByUserIdOrderByDefaultAddressDescIdAsc(user.getId()).stream().findFirst()
                .ifPresent(first -> first.setDefaultAddress(true));
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
