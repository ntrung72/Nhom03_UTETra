document.addEventListener('DOMContentLoaded',()=>{
  document.querySelectorAll('[data-copy-address]').forEach(button=>button.addEventListener('click',async()=>{
    const address=button.dataset.address;
    if(!address)return;
    const label=button.querySelector('[data-copy-label]');
    const feedback=button.parentElement.querySelector('[data-copy-feedback]');
    try{
      if(navigator.clipboard?.writeText){
        await navigator.clipboard.writeText(address);
      }else{
        const input=document.createElement('textarea');
        input.value=address;
        input.style.position='fixed';
        input.style.opacity='0';
        document.body.appendChild(input);
        input.select();
        try{if(!document.execCommand('copy'))throw new Error('Copy failed');}finally{input.remove();}
      }
      label.textContent='Đã sao chép';
      feedback.textContent='Đã sao chép địa chỉ giao hàng.';
    }catch(error){
      label.textContent='Không thể sao chép';
      feedback.textContent='Không thể sao chép địa chỉ. Vui lòng chọn và sao chép thủ công.';
    }
    window.setTimeout(()=>{label.textContent='Sao chép địa chỉ';feedback.textContent='';},2500);
  }));
});
