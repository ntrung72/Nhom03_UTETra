package vn.iotstar.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
    Optional<User> findByUsernameIgnoreCase(String username);
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmailIgnoreCase(String email);

    @org.springframework.data.jpa.repository.Query("""
        select u from User u where :q is null
        or lower(u.username) like lower(concat('%', :q, '%'))
        or lower(u.email) like lower(concat('%', :q, '%'))
        or lower(u.fullName) like lower(concat('%', :q, '%'))
        """)
    org.springframework.data.domain.Page<User> searchManagement(
        @org.springframework.data.repository.query.Param("q") String q,
        org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
        select u from User u where u.id in
          (select s.owner.id from Shop s where s.id in :ids)
          and (:q is null or lower(u.username) like lower(concat('%', :q, '%'))
          or lower(u.email) like lower(concat('%', :q, '%'))
          or lower(u.fullName) like lower(concat('%', :q, '%')))
        """)
    org.springframework.data.domain.Page<User> searchShopOwners(
        @org.springframework.data.repository.query.Param("ids") java.util.List<Long> ids,
        @org.springframework.data.repository.query.Param("q") String q,
        org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
        select count(distinct s.owner.id) from Shop s where s.id in :ids
        """)
    long countOwnersInShops(@org.springframework.data.repository.query.Param("ids") java.util.List<Long> ids);

    @org.springframework.data.jpa.repository.Query("""
        select case when count(s) > 0 then true else false end
        from Shop s where s.owner.id = :userId and s.id in :ids
        """)
    boolean isOwnerOfAssignedShop(@org.springframework.data.repository.query.Param("userId") Long userId,
        @org.springframework.data.repository.query.Param("ids") java.util.List<Long> ids);

    java.util.List<User> findByRoleOrderByFullNameAsc(vn.iotstar.entity.DomainEnums.Role role);

    java.util.List<User> findByRoleAndEnabledTrueAndLockedFalseOrderByFullNameAsc(vn.iotstar.entity.DomainEnums.Role role);
}
