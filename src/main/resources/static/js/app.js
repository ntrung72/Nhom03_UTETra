document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('[data-otp-resend]').forEach(button => {
        let remaining = Number.parseInt(button.dataset.otpCooldown || '0', 10);
        const label = button.querySelector('[data-otp-label]');
        const render = () => {
            const waiting = remaining > 0;
            button.disabled = waiting;
            if (label) label.textContent = waiting ? `Gửi lại sau ${remaining}s` : 'Gửi lại OTP';
        };
        render();
        if (remaining > 0) {
            const timer = window.setInterval(() => {
                remaining -= 1;
                render();
                if (remaining <= 0) window.clearInterval(timer);
            }, 1000);
        }
    });
});

document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('.password-toggle').forEach(button => {
        button.addEventListener('click', () => {
            const input = button.parentElement.querySelector('input');
            const visible = input.type === 'password';
            input.type = visible ? 'text' : 'password';
            button.setAttribute('aria-label', visible ? 'Ẩn mật khẩu' : 'Hiện mật khẩu');
            button.setAttribute('aria-pressed', String(visible));
        });
    });
    const input = document.getElementById('avatarInput');
    let previewUrl;
    input?.addEventListener('change', () => {
        const file = input.files[0];
        if (!file || !['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || file.size > 5 * 1024 * 1024) return;
        if (previewUrl) URL.revokeObjectURL(previewUrl);
        previewUrl = URL.createObjectURL(file);
        const holder = document.querySelector('.account-avatar-preview');
        holder.replaceChildren();
        const img = document.createElement('img');
        img.src = previewUrl; img.alt = 'Ảnh đại diện mới'; holder.append(img);
    });
});

document.addEventListener('DOMContentLoaded', () => {
    const items = Array.from(document.querySelectorAll('[data-cart-item]'));
    const all = document.querySelector('[data-cart-select-all]');
    const shops = Array.from(document.querySelectorAll('[data-cart-shop-toggle]'));
    const update = () => {
        const eligible = items.filter(item => !item.disabled);
        const selected = eligible.filter(item => item.checked);
        const total = selected.reduce((sum, item) => sum + Number(item.dataset.lineTotal || 0), 0);
        const count = document.querySelector('[data-cart-selected-count]');
        const amount = document.querySelector('[data-cart-selected-total]');
        const save = document.querySelector('[data-cart-save-selection]');
        if (count) count.textContent = `${selected.length} dòng được chọn`;
        if (amount) amount.textContent = total.toLocaleString('vi-VN') + ' ₫';
        if (save) save.disabled = !selected.length;
        if (all) { all.checked = eligible.length > 0 && selected.length === eligible.length; all.indeterminate = selected.length > 0 && !all.checked; }
        shops.forEach(toggle => {
            const card = toggle.closest('[data-cart-shop]');
            const lines = eligible.filter(item => item.dataset.shopId === card.dataset.cartShop);
            const checked = lines.filter(item => item.checked).length;
            toggle.checked = lines.length > 0 && checked === lines.length;
            toggle.indeterminate = checked > 0 && !toggle.checked;
            const label = card.querySelector('[data-shop-selection]');
            if (label) label.textContent = `${checked}/${lines.length} dòng được chọn`;
        });
    };
    all?.addEventListener('change', () => { items.filter(item => !item.disabled).forEach(item => item.checked = all.checked); update(); });
    shops.forEach(toggle => toggle.addEventListener('change', () => {
        const card = toggle.closest('[data-cart-shop]');
        items.filter(item => !item.disabled && item.dataset.shopId === card.dataset.cartShop).forEach(item => item.checked = toggle.checked);
        update();
    }));
    items.forEach(item => item.addEventListener('change', update)); update();
    const options = document.querySelector('[data-product-options]');
    if (options) {
        const preview = () => {
            let price = Number(options.dataset.basePrice || 0);
            options.querySelectorAll('input[data-extra-price]:checked').forEach(input => price += Number(input.dataset.extraPrice || 0));
            const total = options.querySelector('[data-unit-preview]');
            if (total) total.textContent = price.toLocaleString('vi-VN') + ' ₫';
        };
        options.addEventListener('change', preview); preview();
    }
});


// Step 37: synchronized browser notification preview, realtime reconnection and server-side unread count.
document.addEventListener('DOMContentLoaded', () => {
    const toastEl = document.getElementById('live-notification');
    if (!toastEl || !window.SockJS || !window.Stomp) return;
    const preview = document.querySelector('[data-nav-notifications]');
    const notificationList = document.querySelector('[data-nav-notification-list]');
    let retryDelay = 1000;
    let retryTimer = null;
    let activeClient = null;
    let stopped = false;

    function showCount(count) {
        let badge = document.querySelector('[data-unread-notifications]');
        const number = Math.max(0, Number(count) || 0);
        if (!badge && number > 0) {
            const button = document.querySelector('.navbar-user-button');
            if (button) {
                badge = document.createElement('span');
                badge.className = 'badge bg-danger ms-1';
                badge.dataset.unreadNotifications = '';
                button.appendChild(badge);
            }
        }
        if (badge) {
            badge.textContent = String(number);
            badge.hidden = number === 0;
        }
    }
    function refreshUnread() {
        fetch('/api/notifications/unread-count', {credentials: 'same-origin', cache: 'no-store'})
            .then(resp => resp.ok ? resp.json() : Promise.reject(resp.status))
            .then(data => showCount(data.count))
            .catch(() => {});
    }
    function safeLink(url) {
        return typeof url === 'string' && url.startsWith('/') && !url.startsWith('//')
            && !/[\\\r\n]/.test(url) ? url : '/user/notifications';
    }
    function refreshPreview() {
        if (!preview || !notificationList) return;
        const url = preview.dataset.previewUrl;
        fetch(url, {credentials: 'same-origin', cache: 'no-store'})
            .then(resp => resp.ok ? resp.json() : Promise.reject(resp.status))
            .then(items => {
                notificationList.replaceChildren();
                if (!items.length) {
                    const li = document.createElement('li');
                    li.className = 'text-muted';
                    li.textContent = 'Chưa có thông báo';
                    notificationList.appendChild(li);
                    return;
                }
                items.forEach(item => {
                    const li = document.createElement('li');
                    li.className = 'border-bottom py-1';
                    const link = document.createElement('a');
                    link.className = 'd-block text-decoration-none text-body';
                    link.href = safeLink(item.link);
                    const strong = document.createElement('strong');
                    strong.textContent = item.title || 'Thông báo';
                    link.append(strong, document.createTextNode(' · ' + (item.message || '')));
                    li.appendChild(link);
                    notificationList.appendChild(li);
                });
            }).catch(() => {});
    }
    function showToast(data) {
        const title = toastEl.querySelector('.toast-header strong');
        const message = toastEl.querySelector('.toast-body');
        if (title) title.textContent = data.title || 'UTETra';
        if (message) message.textContent = data.message || 'Bạn có thông báo mới.';
        window.bootstrap?.Toast.getOrCreateInstance(toastEl).show();
    }
    function appendOrderStatus(data) {
        const match = window.location.pathname.match(/^\/orders\/(\d+)\/?$/);
        if (!match || String(data.orderId) !== match[1]) return;
        const historyHeading = Array.from(document.querySelectorAll('h2'))
            .find(el => el.textContent.includes('Lịch sử trạng thái'));
        const timeline = historyHeading?.parentElement?.querySelector('ol');
        if (!timeline) return;
        const item = document.createElement('li');
        item.className = 'border-start border-primary ps-3 py-2';
        const label = document.createElement('strong');
        label.textContent = data.label || data.status;
        const time = document.createElement('time');
        time.className = 'd-block small text-muted';
        time.textContent = data.changedAt || '';
        const meta = document.createElement('span');
        meta.className = 'small d-block';
        meta.textContent = (data.changedBy || '') + (data.note ? ' · ' + data.note : '');
        item.append(label, time, meta);
        timeline.appendChild(item);
    }
    function reconnect() {
        if (stopped || retryTimer) return;
        retryTimer = setTimeout(() => {
            retryTimer = null;
            connect();
        }, retryDelay);
        retryDelay = Math.min(retryDelay * 2, 30000);
    }
    function connect() {
        if (stopped || document.visibilityState === 'hidden') return;
        try {
            const client = window.Stomp.over(new window.SockJS('/ws'));
            activeClient = client;
            client.debug = () => {};
            client.connect({}, () => {
                if (activeClient !== client) return;
                retryDelay = 1000;
                refreshUnread();
                refreshPreview();
                client.subscribe('/user/queue/notifications', frame => {
                    if (activeClient !== client) return;
                    try {
                        const data = JSON.parse(frame.body);
                        showToast(data);
                        refreshUnread();
                        refreshPreview();
                    } catch (_) {}
                });
                client.subscribe('/user/queue/order-status', frame => {
                    try { appendOrderStatus(JSON.parse(frame.body)); } catch (_) {}
                });
            }, () => {
                if (activeClient === client) { activeClient = null; reconnect(); }
            });
        } catch (_) { reconnect(); }
    }
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') {
            refreshUnread();
            refreshPreview();
            if (!activeClient || !activeClient.connected) reconnect();
        }
    });
    window.addEventListener('beforeunload', () => { stopped = true; if (retryTimer) clearTimeout(retryTimer); });
    refreshUnread();
    refreshPreview();
    connect();
});


document.addEventListener('DOMContentLoaded', () => {
    const currentPath = window.location.pathname.replace(/\/$/, '') || '/';
    const linkPath = link => new URL(link.href, window.location.origin).pathname.replace(/\/$/, '') || '/';
    const markActive = element => {
        if (!element) return;
        element.classList.add('active');
        if (element.tagName === 'A') element.setAttribute('aria-current', 'page');
    };
    document.querySelectorAll('.side-nav[aria-label="Điều hướng người bán"] a[href]').forEach(link => {
        const target = linkPath(link);
        const exactHome = /\/vendor$/.test(target);
        if (currentPath === target || (!exactHome && currentPath.startsWith(`${target}/`))) {
            markActive(link);
            markActive(link.closest('.dropdown')?.querySelector('.side-nav-dropdown-toggle'));
        }
    });
});
