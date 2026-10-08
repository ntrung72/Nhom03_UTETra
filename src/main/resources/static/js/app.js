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
