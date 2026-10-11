document.addEventListener('DOMContentLoaded',()=>{
  document.querySelectorAll('[data-otp-resend]').forEach(button=>{
    let remaining=Number.parseInt(button.dataset.otpCooldown||'0',10);
    const label=button.querySelector('[data-otp-label]');
    const render=()=>{
      const waiting=remaining>0;
      button.disabled=waiting;
      if(label) label.textContent=waiting?`Gửi lại sau ${remaining}s`:'Gửi lại OTP';
    };
    render();
    if(remaining>0){
      const timer=window.setInterval(()=>{
        remaining-=1;
        render();
        if(remaining<=0) window.clearInterval(timer);
      },1000);
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
