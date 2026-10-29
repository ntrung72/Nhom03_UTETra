document.addEventListener('DOMContentLoaded',()=>{
  const galleryMain=document.getElementById('productGalleryMain');
  const thumbnails=[...document.querySelectorAll('.product-gallery-thumb')];
  thumbnails.forEach(button=>button.addEventListener('click',()=>{
    const image=button.querySelector('img');
    if(!galleryMain||!image)return;
    galleryMain.src=image.src;
    galleryMain.alt=image.alt;
    thumbnails.forEach(item=>{
      const selected=item===button;
      item.classList.toggle('active',selected);
      item.setAttribute('aria-pressed',String(selected));
    });
  }));

  const favoriteForm=document.querySelector('[data-favorite-toggle-url]');
  if(favoriteForm&&window.fetch){
    const button=favoriteForm.querySelector('button');
    const icon=button?.querySelector('i');
    const label=favoriteForm.querySelector('[data-favorite-label]');
    const count=favoriteForm.querySelector('[data-favorite-count]');
    const feedback=favoriteForm.querySelector('[data-favorite-feedback]');
    let feedbackTimer;
    favoriteForm.addEventListener('submit',async event=>{
      event.preventDefault();
      if(!button||button.disabled)return;
      button.disabled=true;
      if(feedback)feedback.textContent='';
      try{
        const response=await fetch(favoriteForm.dataset.favoriteToggleUrl,{
          method:'POST',body:new FormData(favoriteForm),credentials:'same-origin',
          headers:{Accept:'application/json'}
        });
        if(response.redirected&&new URL(response.url).pathname==='/login'){
          window.location.assign(response.url);
          return;
        }
        if(!response.ok)throw new Error('Không cập nhật được lượt thích.');
        const result=await response.json();
        const total=Number(result.count);
        if(typeof result.liked!=='boolean'||!Number.isSafeInteger(total)||total<0)throw new Error('Dữ liệu lượt thích không hợp lệ.');
        button.classList.toggle('is-favorite',result.liked);
        button.setAttribute('aria-pressed',String(result.liked));
        button.setAttribute('aria-label',`${result.liked?'Bỏ thích':'Thích'} sản phẩm, ${total} lượt thích`);
        if(icon){icon.classList.toggle('bi-heart-fill',result.liked);icon.classList.toggle('bi-heart',!result.liked);}
        if(label)label.textContent=result.liked?'Đã thích':'Thích';
        if(count)count.textContent=new Intl.NumberFormat('vi-VN').format(total);
        if(feedback){
          feedback.textContent=result.liked?'Đã thêm vào yêu thích.':'Đã bỏ yêu thích.';
          clearTimeout(feedbackTimer);
          feedbackTimer=window.setTimeout(()=>{feedback.textContent='';},2200);
        }
      }catch{
        if(feedback)feedback.textContent='Chưa thể cập nhật lượt thích. Vui lòng thử lại.';
      }finally{button.disabled=false;}
    });
  }

  const form=document.querySelector('.detail-options');
  if(form){
    const base=Number(form.dataset.basePrice||0);
    const saleBase=Number(form.dataset.saleBasePrice||base);
    const stock=Math.max(1,Number(form.dataset.stock)||1);
    const quantity=form.querySelector('input[name="quantity"]');
    const money=value=>new Intl.NumberFormat('vi-VN',{maximumFractionDigits:0}).format(value)+' ₫';
    const selectedQuantity=()=>Math.min(stock,Math.max(1,Math.trunc(Number(quantity?.value)||1)));
    const refresh=()=>{
      const count=selectedQuantity();
      let optionPrice=0;
      form.querySelectorAll('[data-extra-price]:checked').forEach(input=>{
        optionPrice+=Number(input.dataset.extraPrice||0);
      });
      const unitPrice=saleBase+optionPrice;
      const originalPrice=base+optionPrice;
      const values={
        configuredPrice:money(unitPrice),
        configuredOriginalPrice:money(originalPrice),
        detailUnitPrice:money(unitPrice),
        detailEstimatedTotal:money(unitPrice*count),
        detailQuantityLabel:String(count),
        detailSavings:money(Math.max(0,base-saleBase)*count)
      };
      Object.entries(values).forEach(([id,value])=>{
        const target=document.getElementById(id);
        if(target)target.textContent=value;
      });
      const minus=form.querySelector('[data-quantity-minus]');
      const plus=form.querySelector('[data-quantity-plus]');
      if(minus)minus.disabled=count<=1;
      if(plus)plus.disabled=count>=stock;
    };
    const presets=[...form.querySelectorAll('[data-product-preset]')];
    const choose=(name,value)=>{
      const option=[...form.querySelectorAll('input[type="radio"]')]
        .find(input=>input.name===name&&input.value===value);
      if(option)option.checked=true;
    };
    presets.forEach(button=>button.addEventListener('click',()=>{
      const firstSize=form.querySelector('input[name="sizeId"]');
      if(firstSize)firstSize.checked=true;
      choose('sugarLevel',button.dataset.productPreset==='light'?'PERCENT_50':'PERCENT_100');
      choose('iceLevel',button.dataset.productPreset==='light'?'LESS_ICE':'NORMAL');
      form.querySelectorAll('input[name="toppingIds"]').forEach(input=>{input.checked=false;});
      presets.forEach(item=>item.classList.toggle('active',item===button));
      savedRecipes.forEach(item=>item.classList.remove('active'));
      refresh();
    }));
    const savedRecipes=[...form.querySelectorAll('[data-saved-recipe]')];
    savedRecipes.forEach(button=>button.addEventListener('click',()=>{
      choose('sizeId',button.dataset.recipeSizeId);
      choose('sugarLevel',button.dataset.recipeSugar);
      choose('iceLevel',button.dataset.recipeIce);
      const selectedToppings=new Set((button.dataset.recipeToppings||'').split(',').filter(Boolean));
      form.querySelectorAll('input[name="toppingIds"]').forEach(input=>{
        input.checked=selectedToppings.has(input.value);
      });
      presets.forEach(item=>item.classList.remove('active'));
      savedRecipes.forEach(item=>item.classList.toggle('active',item===button));
      refresh();
    }));
    form.addEventListener('change',event=>{
      if(event.target.matches('input[type="radio"],input[type="checkbox"]')){
        presets.forEach(button=>button.classList.remove('active'));
        savedRecipes.forEach(button=>button.classList.remove('active'));
      }
      if(event.target===quantity)quantity.value=String(selectedQuantity());
      refresh();
    });
    quantity?.addEventListener('input',refresh);
    form.querySelector('[data-quantity-minus]')?.addEventListener('click',()=>{
      quantity.value=String(Math.max(1,selectedQuantity()-1));
      refresh();
    });
    form.querySelector('[data-quantity-plus]')?.addEventListener('click',()=>{
      quantity.value=String(Math.min(stock,selectedQuantity()+1));
      refresh();
    });
    form.addEventListener('submit',()=>{
      quantity.value=String(selectedQuantity());
    });
    refresh();
  }

  const lightbox=document.getElementById('reviewLightbox');
  const enlarged=lightbox?.querySelector('img');
  document.querySelectorAll('[data-review-lightbox]').forEach(button=>button.addEventListener('click',()=>{
    if(!lightbox||!enlarged)return;
    enlarged.src=button.dataset.imageUrl||'';
    enlarged.alt=button.dataset.imageAlt||'Ảnh đánh giá phóng lớn';
    if(typeof lightbox.showModal==='function')lightbox.showModal();
  }));
  lightbox?.querySelector('[data-review-lightbox-close]')?.addEventListener('click',()=>lightbox.close());
  lightbox?.addEventListener('click',event=>{if(event.target===lightbox)lightbox.close();});
  lightbox?.addEventListener('close',()=>{if(enlarged)enlarged.removeAttribute('src');});
});
