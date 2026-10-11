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
