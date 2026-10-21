(() => {
    'use strict';

    const wholeNumber = new Intl.NumberFormat('vi-VN', {
        maximumFractionDigits: 0
    });

    // Dùng số nguyên theo đơn vị 0,01 đồng để tránh sai số cộng số thực.
    function toCents(raw) {
        const value = String(raw ?? '').trim();

        if (value.length > 32 || !/^\d+(?:\.\d{1,2})?$/.test(value)) {
            return null;
        }

        const [whole, fraction = ''] = value.split('.');

        return BigInt(whole) * 100n
            + BigInt(fraction.padEnd(2, '0'));
    }

    function formatMoney(cents) {
        const whole = cents / 100n;
        const fraction = cents % 100n;

        const suffix = fraction === 0n
            ? ''
            : ',' + fraction.toString().padStart(2, '0');

        return wholeNumber.format(whole) + suffix + ' ₫';
    }

    function initializeOptions(form) {
        if (form.dataset.productDetailReady === 'true') {
            return;
        }

        form.dataset.productDetailReady = 'true';

        const preview = form.querySelector('[data-unit-preview]');

        if (!preview) {
            return;
        }

        function refresh() {
            const basePrice = toCents(form.dataset.basePrice);

            if (basePrice === null) {
                preview.textContent = 'Chưa thể tính giá';
                return;
            }

            const selectedSize = form.querySelector(
                'input[name="sizeId"]:checked:not(:disabled)'
            );

            if (form.dataset.requiresSize === 'true' && !selectedSize) {
                preview.textContent = 'Vui lòng chọn size';
                return;
            }

            let price = basePrice;

            if (selectedSize) {
                const extraPrice = toCents(
                    selectedSize.dataset.extraPrice
                );

                if (extraPrice === null) {
                    preview.textContent = 'Chưa thể tính giá';
                    return;
                }

                price += extraPrice;
            }

            const selectedToppings = new Set();

            const inputs = form.querySelectorAll(
                'input[name="toppingIds"]:checked:not(:disabled)'
            );

            for (const input of inputs) {
                // Backend cũng chỉ tính mỗi topping được chọn một lần.
                if (selectedToppings.has(input.value)) {
                    continue;
                }

                selectedToppings.add(input.value);

                const extraPrice = toCents(input.dataset.extraPrice);

                if (extraPrice === null) {
                    preview.textContent = 'Chưa thể tính giá';
                    return;
                }

                price += extraPrice;
            }

            // Đây là đơn giá; số lượng không làm đổi giá của một sản phẩm.
            preview.textContent = formatMoney(price);
        }

        form.addEventListener('change', refresh);
        window.addEventListener('pageshow', refresh);

        refresh();
    }

    function initializeGallery(root) {
        if (root.dataset.productGalleryReady === 'true') {
            return;
        }

        root.dataset.productGalleryReady = 'true';

        const main = root.querySelector('#productGalleryMain');

        if (!main) {
            return;
        }

        const placeholder = root.dataset.placeholderUrl;

        const buttons = Array.from(
            root.querySelectorAll('.product-gallery-thumb')
        );

        function selectButton(selectedButton) {
            buttons.forEach(button => {
                const selected =
                    button === selectedButton && !button.disabled;

                button.classList.toggle('active', selected);
                button.classList.toggle('border-primary', selected);
                button.setAttribute('aria-pressed', String(selected));
            });
        }

        function usePlaceholder(image) {
            // Chặn lặp vô hạn nếu chính ảnh placeholder cũng không tải được.
            if (!placeholder || image.dataset.fallbackApplied === 'true') {
                return;
            }

            image.dataset.fallbackApplied = 'true';
            image.removeAttribute('srcset');
            image.src = placeholder;
        }

        function watchImage(image, button) {
            const onError = () => {
                if (button) {
                    button.disabled = true;

                    button.classList.remove(
                        'active', 'border-primary'
                    );

                    button.setAttribute('aria-pressed', 'false');

                    button.setAttribute(
                        'aria-label',
                        'Ảnh sản phẩm không tải được'
                    );
                }

                usePlaceholder(image);
            };

            image.addEventListener('error', onError);

            // Ảnh trong cache có thể đã lỗi trước khi script được khởi tạo.
            if (image.complete
                && image.naturalWidth === 0
                && image.getAttribute('src')) {
                onError();
            }
        }

        watchImage(main, null);

        buttons.forEach(button => {
            const thumbnail = button.querySelector('img');

            if (!thumbnail) {
                button.disabled = true;
                return;
            }

            watchImage(thumbnail, button);

            button.addEventListener('click', () => {
                if (button.disabled) {
                    return;
                }

                const source = thumbnail.currentSrc || thumbnail.src;

                if (!source) {
                    return;
                }

                main.dataset.fallbackApplied = 'false';
                main.removeAttribute('srcset');
                main.alt = thumbnail.alt;
                main.src = source;

                selectButton(button);
            });
        });
    }

    function initialize() {
        document.querySelectorAll('[data-product-gallery]')
            .forEach(initializeGallery);

        document.querySelectorAll('[data-product-detail-options]')
            .forEach(initializeOptions);
    }

    if (document.readyState === 'loading') {
        document.addEventListener(
            'DOMContentLoaded',
            initialize,
            { once: true }
        );
    } else {
        initialize();
    }
})();