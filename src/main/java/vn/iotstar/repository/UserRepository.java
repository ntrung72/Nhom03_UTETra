package vn.iotstar.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.User;
import vn.iotstar.entity.DomainEnums.Role;

public interface UserRepository extends JpaRepository<User, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
    Optional<User> findByUsernameIgnoreCase(String username);
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmailIgnoreCase(String email);
    java.util.List<User> findByRoleAndEnabledTrueAndLockedFalseOrderByFullNameAsc(Role role);
    java.util.List<User> findByRoleOrderByFullNameAsc(Role role);

    @Query("""
        select u from User u
        where :q is null or lower(u.username) like lower(concat('%', :q, '%'))
           or lower(u.email) like lower(concat('%', :q, '%'))
           or lower(u.fullName) like lower(concat('%', :q, '%'))
        """)
    Page<User> search(@Param("q") String q, Pageable pageable);

    @Query("""
        select distinct u from User u
        where (exists (select o.id from CustomerOrder o where o.user = u and o.shop.id in :shopIds)
               or exists (select s.id from Shop s where s.owner = u and s.id in :shopIds))
          and (:q is null or lower(u.username) like lower(concat('%', :q, '%'))
            or lower(u.email) like lower(concat('%', :q, '%'))
            or lower(u.fullName) like lower(concat('%', :q, '%')))
        """)
    Page<User> searchForAssignedShops(@Param("shopIds") java.util.List<Long> shopIds,
                                      @Param("q") String q, Pageable pageable);

    @Query("""
        select (count(u) > 0) from User u where u.id = :userId
          and (exists (select o.id from CustomerOrder o where o.user = u and o.shop.id in :shopIds)
               or exists (select s.id from Shop s where s.owner = u and s.id in :shopIds))
        """)
    boolean isUserInAssignedShops(@Param("userId") Long userId, @Param("shopIds") java.util.List<Long> shopIds);
}
