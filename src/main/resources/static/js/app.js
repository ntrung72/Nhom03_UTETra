document.addEventListener('DOMContentLoaded',()=>{
  const currentPath=window.location.pathname.replace(/\/$/,'')||'/';
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
  if(!window.bootstrap){
    document.querySelectorAll('[data-bs-toggle="collapse"]').forEach(button=>button.addEventListener('click',()=>{
      const target=document.querySelector(button.getAttribute('data-bs-target'));
      if(!target)return;
      const open=target.classList.toggle('show');
      button.setAttribute('aria-expanded',String(open));
    }));
    document.querySelectorAll('[data-bs-toggle="dropdown"]').forEach(button=>button.addEventListener('click',event=>{
      event.stopPropagation();
      const menu=button.closest('.dropdown')?.querySelector('.dropdown-menu');
      if(!menu)return;
      const open=!menu.classList.contains('show');
      document.querySelectorAll('.dropdown-menu.show').forEach(other=>other.classList.remove('show'));
      document.querySelectorAll('[data-bs-toggle="dropdown"]').forEach(other=>other.setAttribute('aria-expanded','false'));
      menu.classList.toggle('show',open);
      button.setAttribute('aria-expanded',String(open));
    }));
    document.addEventListener('click',event=>{
      if(event.target.closest('.dropdown'))return;
      document.querySelectorAll('.dropdown-menu.show').forEach(menu=>menu.classList.remove('show'));
      document.querySelectorAll('[data-bs-toggle="dropdown"]').forEach(button=>button.setAttribute('aria-expanded','false'));
    });
  }
  const linkPath=link=>new URL(link.href,window.location.origin).pathname.replace(/\/$/,'')||'/';
  const markActive=element=>{
    if(!element)return;
    element.classList.add('active');
    if(element.tagName==='A')element.setAttribute('aria-current','page');
  };
  const homeLink=document.querySelector('[data-nav-home]');
  if(currentPath==='/')markActive(homeLink);
  if(currentPath==='/products'||currentPath.startsWith('/products/')||currentPath==='/shops'||currentPath.startsWith('/shops/')){
    markActive(document.querySelector('[data-nav-catalog]'));
    if(currentPath==='/shops'||currentPath.startsWith('/shops/')){
      markActive([...document.querySelectorAll('.navbar-catalog-menu a[href]')].find(link=>linkPath(link)==='/shops'));
    }else if(currentPath.startsWith('/products/')){
      markActive([...document.querySelectorAll('.navbar-catalog-menu a[href]')].find(link=>linkPath(link)==='/products'));
    }else if(currentPath==='/products'){
      const selectedCategory=new URLSearchParams(window.location.search).get('category');
      document.querySelectorAll('.navbar-catalog-menu a[href]').forEach(link=>{
        const url=new URL(link.href,window.location.origin);
        if(url.pathname==='/products'&&url.searchParams.get('category')===selectedCategory)markActive(link);
      });
    }
  }
  if(currentPath==='/cart'||currentPath.startsWith('/cart/')||currentPath==='/checkout')markActive(document.querySelector('[data-nav-cart]'));
  if(currentPath==='/notifications'||currentPath.startsWith('/notifications/'))markActive(document.querySelector('[data-nav-notifications]'));
  if(currentPath==='/orders'||currentPath.startsWith('/orders/')||currentPath==='/user'||currentPath.startsWith('/user/')||currentPath==='/vendor'||currentPath.startsWith('/vendor/')||currentPath==='/management'||currentPath.startsWith('/management/')||currentPath==='/shipper'||currentPath.startsWith('/shipper/')){
    markActive(document.querySelector('[data-nav-account]'));
  }
  document.querySelectorAll('.navbar-account-menu a[href]').forEach(link=>{
    const target=linkPath(link);
    if(currentPath===target||currentPath.startsWith(`${target}/`))markActive(link);
  });
  document.querySelectorAll('.side-nav a').forEach(link=>{
    const target=linkPath(link);
    const exactHome=target==='/vendor'||target==='/management';
    if(currentPath===target||(!exactHome&&currentPath.startsWith(`${target}/`))){
      markActive(link);
      const group=link.closest('.management-nav-group');
      if(group){group.open=true;markActive(group.querySelector('summary'));}
      markActive(link.closest('.dropdown')?.querySelector('.side-nav-dropdown-toggle'));
    }
  });
  document.querySelectorAll('.dashboard-side-nav').forEach(nav=>{
    const toggle=nav.querySelector('.management-nav-toggle');
    nav.classList.add('is-enhanced');
    toggle?.addEventListener('click',()=>{
      const open=nav.classList.toggle('is-open');
      toggle.setAttribute('aria-expanded',String(open));
      const label=nav.classList.contains('vendor-side-nav')?'người bán':'quản trị';
      toggle.setAttribute('aria-label',`${open?'Đóng':'Mở'} menu ${label}`);
    });
  });
  const breadcrumb=document.querySelector('[data-site-breadcrumb]');
  const breadcrumbList=document.querySelector('[data-breadcrumb-list]');
  if(breadcrumb&&breadcrumbList&&currentPath!=='/'){
    const title=document.title.replace(/\s*\|\s*UTETra\s*$/,'').trim()||'Trang hiện tại';
    const crumbs=[{label:'Trang chủ',href:'/'}];
    if(currentPath.startsWith('/products/'))crumbs.push({label:'Sản phẩm',href:'/products'});
    else if(currentPath.startsWith('/shops/'))crumbs.push({label:'Cửa hàng',href:'/shops'});
    else if(currentPath==='/checkout')crumbs.push({label:'Giỏ hàng',href:'/cart'});
    else if(currentPath.startsWith('/orders/'))crumbs.push({label:'Đơn mua',href:'/orders'});
    else if(currentPath.startsWith('/user/'))crumbs.push({label:'Tài khoản',href:'/user/profile'});
    else if(currentPath.startsWith('/vendor/'))crumbs.push({label:'Kênh người bán',href:'/vendor'});
    else if(currentPath.startsWith('/management/'))crumbs.push({label:'Quản trị',href:'/management'});
    else if(currentPath.startsWith('/shipper/'))crumbs.push({label:'Giao hàng',href:'/shipper'});
    const fallback={"/products":"Sản phẩm","/shops":"Cửa hàng","/cart":"Giỏ hàng","/orders":"Đơn mua","/user":"Tài khoản","/vendor":"Kênh người bán","/management":"Quản trị","/shipper":"Giao hàng","/policies":"Chính sách và hỗ trợ"};
    const currentLabel=fallback[currentPath]||title;
    if(crumbs.at(-1)?.href===currentPath)crumbs.pop();
    const item=document.createElement('li');
    item.className='breadcrumb-item active';
    item.setAttribute('aria-current','page');
    item.textContent=currentLabel;
    crumbs.forEach(crumb=>{
      const li=document.createElement('li');
      li.className='breadcrumb-item';
      const a=document.createElement('a');
      a.href=crumb.href;
      a.textContent=crumb.label;
      li.append(a);
      breadcrumbList.append(li);
    });
    breadcrumbList.append(item);
    breadcrumb.classList.remove('d-none');
  }
  document.querySelectorAll('[data-current-year]').forEach(node=>{node.textContent=String(new Date().getFullYear());});
  const catalogFilterToggle=document.querySelector('[data-catalog-filter-toggle]');
  const catalogFilterForm=document.querySelector('[data-catalog-filter-form]');
  if(catalogFilterToggle&&catalogFilterForm){
    document.body.classList.add('catalog-filter-enhanced');
    const hasFilterError=Boolean(catalogFilterForm.querySelector('.catalog-filter-error'));
    catalogFilterForm.classList.toggle('is-open',hasFilterError);
    catalogFilterToggle.setAttribute('aria-expanded',String(hasFilterError));
    catalogFilterToggle.addEventListener('click',()=>{
      const open=catalogFilterForm.classList.toggle('is-open');
      catalogFilterToggle.setAttribute('aria-expanded',String(open));
    });
  }
  document.querySelectorAll('form[data-catalog-sort]').forEach(form=>{
    form.classList.add('catalog-sort-enhanced');
    form.querySelector('select[name="sort"]')?.addEventListener('change',()=>form.requestSubmit());
  });
  document.querySelectorAll('form[data-smart-search]').forEach((form,index)=>{
    const input=form.querySelector('input[name="q"]');
    if(!input)return;
    const host=form.querySelector('[data-search-host]')||form;
    host.classList.add('smart-search-host');
    const panel=document.createElement('div');
    panel.id=`smart-search-panel-${index}`;
    panel.className='smart-search-panel';
    panel.setAttribute('role','listbox');
    panel.setAttribute('aria-label','Gợi ý tìm kiếm');
    panel.hidden=true;
    host.append(panel);
    input.setAttribute('aria-controls',panel.id);
    const productsUrl=new URL(form.action,window.location.href);
    const shopsUrl=new URL(form.dataset.shopsUrl,window.location.href);
    const historyUrl=new URL(form.dataset.historyUrl,window.location.href);
    let debounceTimer=null;
    let requestController=null;
    let suppressFocusLoad=false;
    const open=()=>{panel.hidden=false;input.setAttribute('aria-expanded','true');};
    const close=()=>{
      clearTimeout(debounceTimer);
      requestController?.abort();
      panel.hidden=true;
      input.setAttribute('aria-expanded','false');
    };
    const searchUrl=keyword=>{
      const url=new URL(productsUrl);
      url.searchParams.set('q',keyword);
      return url.href;
    };
    const addRow=(container,icon,title,detail,href)=>{
      const link=document.createElement('a');
      link.className='smart-search-row';
      link.setAttribute('role','option');
      link.href=href;
      const symbol=document.createElement('i');
      symbol.className=`bi ${icon}`;
      symbol.setAttribute('aria-hidden','true');
      const text=document.createElement('span');
      const label=document.createElement('strong');
      label.textContent=String(title||'');
      text.append(label);
      if(detail){
        const meta=document.createElement('small');
        meta.textContent=String(detail);
        text.append(meta);
      }
      link.append(symbol,text);
      container.append(link);
    };
    const addHistoryRow=(section,keyword)=>{
      const item=document.createElement('div');
      item.className='smart-search-history-item';
      addRow(item,'bi-clock-history',keyword,'Lịch sử của bạn',searchUrl(keyword));
      const remove=document.createElement('button');
      remove.type='button';
      remove.className='smart-search-history-remove';
      remove.setAttribute('aria-label',`Xóa tìm kiếm gần đây: ${keyword}`);
      remove.title='Xóa khỏi lịch sử tìm kiếm';
      remove.innerHTML='<i class="bi bi-x-lg" aria-hidden="true"></i>';
      remove.addEventListener('click',async()=>{
        remove.disabled=true;
        try{
          const url=new URL(historyUrl);
          url.searchParams.set('keyword',keyword);
          const response=await fetch(url,{method:'DELETE',credentials:'same-origin',headers:{Accept:'application/json'}});
          if(!response.ok)throw new Error('Không thể xóa tìm kiếm. Vui lòng thử lại.');
          item.remove();
          if(!section.querySelector('.smart-search-history-item'))section.remove();
        }catch(error){
          remove.disabled=false;
          const message=document.createElement('small');
          message.className='smart-search-delete-error';
          message.setAttribute('role','alert');
          message.textContent=error.message||'Không thể xóa tìm kiếm. Vui lòng thử lại.';
          item.after(message);
          window.setTimeout(()=>message.remove(),3000);
        }
      });
      item.append(remove);
      section.append(item);
    };
    const addSection=(title,items,render)=>{
      if(!Array.isArray(items)||!items.length)return;
      const section=document.createElement('section');
      section.className='smart-search-section';
      section.setAttribute('role','group');
      section.setAttribute('aria-label',title);
      const heading=document.createElement('div');
      heading.className='smart-search-heading';
      heading.textContent=title;
      section.append(heading);
      items.forEach(item=>render(section,item));
      panel.append(section);
    };
    const render=(data,query)=>{
      panel.replaceChildren();
      if(!query){
        addSection('Tìm kiếm gần đây',data.history?.slice(0,5),addHistoryRow);
      }
      addSection(data.popular?'Sản phẩm bán chạy':'Sản phẩm',data.products?.slice(0,5),(section,product)=>{
        const id=Number(product.id);
        if(Number.isSafeInteger(id)&&id>0){
          addRow(section,'bi-cup-straw',product.name,product.shopName,`${productsUrl.pathname.replace(/\/$/,'')}/${id}`);
        }
      });
      addSection('Cửa hàng',data.shops?.slice(0,3),(section,shop)=>
        addRow(section,'bi-shop',shop.name,'Xem cửa hàng',
          `${shopsUrl.pathname.replace(/\/$/,'')}/${encodeURIComponent(shop.slug)}`));
      addSection('Từ khóa liên quan',data.keywords?.slice(0,5),(section,keyword)=>
        addRow(section,'bi-search',keyword,'Tìm sản phẩm',searchUrl(keyword)));
      if(query){
        addSection('Tìm kiếm gần đây',data.history?.slice(0,5),addHistoryRow);
      }
      if(!panel.children.length){
        const empty=document.createElement('p');
        empty.className='smart-search-empty';
        empty.textContent=query?'Chưa có gợi ý phù hợp. Bạn vẫn có thể tìm toàn bộ sản phẩm.':'Chưa có gợi ý nào.';
        panel.append(empty);
      }
      if(query){
        const all=document.createElement('a');
        all.className='smart-search-all';
        all.setAttribute('role','option');
        all.href=searchUrl(query);
        all.textContent=`Xem tất cả kết quả cho “${query}”`;
        panel.append(all);
      }
      open();
    };
    const load=async()=>{
      requestController?.abort();
      requestController=new AbortController();
      const activeRequest=requestController;
      const query=input.value.trim().slice(0,80);
      panel.textContent='Đang tìm gợi ý…';
      open();
      try{
        const url=new URL(form.dataset.suggestUrl,window.location.href);
        if(query)url.searchParams.set('q',query);
        const response=await fetch(url,{credentials:'same-origin',cache:'no-store',signal:activeRequest.signal,headers:{Accept:'application/json'}});
        if(!response.ok)throw new Error('Không tải được gợi ý. Nhấn Enter để tìm kiếm.');
        const data=await response.json();
        if(!activeRequest.signal.aborted&&(document.activeElement===input||panel.contains(document.activeElement)))render(data,query);
      }catch(error){
        if(activeRequest.signal.aborted)return;
        panel.textContent=error.message||'Không tải được gợi ý. Nhấn Enter để tìm kiếm.';
        open();
      }
    };
    input.addEventListener('focus',()=>{
      if(suppressFocusLoad){suppressFocusLoad=false;return;}
      clearTimeout(debounceTimer);load();
    });
    input.addEventListener('input',()=>{
      clearTimeout(debounceTimer);
      requestController?.abort();
      panel.textContent='Đang tìm gợi ý…';
      open();
      debounceTimer=setTimeout(load,240);
    });
    host.addEventListener('keydown',event=>{
      if(event.key==='Escape'){
        close();
        if(document.activeElement!==input){suppressFocusLoad=true;input.focus();}
        return;
      }
      if(event.key!=='ArrowDown'&&event.key!=='ArrowUp')return;
      const links=[...panel.querySelectorAll('a')];
      if(panel.hidden||!links.length)return;
      const current=links.indexOf(document.activeElement);
      if(event.key==='ArrowDown'&&current<links.length-1){event.preventDefault();links[current+1].focus();}
      if(event.key==='ArrowUp'&&current>=0){
        event.preventDefault();
        if(current===0){suppressFocusLoad=true;input.focus();}else links[current-1].focus();
      }
    });
    document.addEventListener('pointerdown',event=>{if(!host.contains(event.target))close();});
    document.addEventListener('focusin',event=>{if(!host.contains(event.target))close();});
    form.addEventListener('submit',()=>{clearTimeout(debounceTimer);requestController?.abort();close();});
  });
  const confirmDialog=document.getElementById('siteConfirmDialog');
  const confirmedForms=new WeakSet();
  let pendingConfirmation=null;
  const submitConfirmed=({form,submitter})=>{
    confirmedForms.add(form);
    if(submitter?.isConnected)form.requestSubmit(submitter);
    else form.requestSubmit();
  };
  confirmDialog?.addEventListener('close',()=>{
    const pending=pendingConfirmation;
    pendingConfirmation=null;
    if(confirmDialog.returnValue==='confirm'&&pending)submitConfirmed(pending);
  });
  document.querySelectorAll('form[data-confirm], form.confirm-form').forEach(form=>form.addEventListener('submit',event=>{
    if(confirmedForms.has(form)){confirmedForms.delete(form);return;}
    event.preventDefault();
    const message=form.dataset.confirm||'Bạn chắc chắn muốn thực hiện thao tác này?';
    if(confirmDialog?.showModal){
      pendingConfirmation={form,submitter:event.submitter};
      confirmDialog.querySelector('#siteConfirmMessage').textContent=message;
      confirmDialog.returnValue='cancel';
      confirmDialog.showModal();
    }else if(window.confirm(message))submitConfirmed({form,submitter:event.submitter});
  }));
  document.addEventListener('submit',event=>{
    const form=event.target;
    if(event.defaultPrevented||form.tagName!=='FORM'||form.method.toLowerCase()!=='post'
      ||form.id==='checkoutForm'||form.hasAttribute('data-no-global-loading')||form.closest('#siteConfirmDialog'))return;
    if(form.dataset.submitting==='true'){event.preventDefault();return;}
    form.dataset.submitting='true';
    const button=event.submitter||form.querySelector('button[type="submit"],input[type="submit"]');
    if(button){
      button.classList.add('is-loading');
      button.setAttribute('aria-busy','true');
      if(button.tagName==='BUTTON'){
        const spinner=document.createElement('span');
        spinner.className='spinner-border spinner-border-sm';
        spinner.setAttribute('aria-hidden','true');
        button.prepend(spinner);
      }
    }
  });
  window.addEventListener('pageshow',()=>{
    document.querySelectorAll('form[data-submitting="true"]').forEach(form=>{
      delete form.dataset.submitting;
      form.querySelectorAll('.is-loading').forEach(button=>{
        button.classList.remove('is-loading');
        button.removeAttribute('aria-busy');
        button.querySelector('.spinner-border')?.remove();
      });
    });
  });
  const imageFallback=image=>{
    if(image.dataset.fallbackApplied==='true'||image.closest('.toast-header')||image.closest('.footer-brand')||image.closest('.navbar-brand'))return;
    image.dataset.fallbackApplied='true';
    if(image.closest('.navbar-avatar, .account-avatar')){
      image.hidden=true;
      if(!image.parentElement.querySelector('.bi-person-fill')){
        const icon=document.createElement('i');
        icon.className='bi bi-person-fill';
        icon.setAttribute('aria-hidden','true');
        image.parentElement.append(icon);
      }
      return;
    }
    if(image.currentSrc.includes('/private-media/')){
      image.hidden=true;
      const notice=document.createElement('span');
      notice.className='image-unavailable';
      notice.textContent='Ảnh bằng chứng hiện không tải được';
      image.insertAdjacentElement('afterend',notice);
      return;
    }
    const source=image.closest('.home-category-card, .category-card')?'category-placeholder.svg'
      :image.closest('.shop-card, .home-shop-card, .shop-public-hero, .followed-shop-cover, .shop-list-image')?'shop-placeholder.svg'
      :'product-placeholder.svg';
    image.src=`/images/${source}`;
    image.classList.add('is-image-fallback');
    image.title='Ảnh hiện không tải được';
  };
  document.addEventListener('error',event=>{
    if(event.target instanceof HTMLImageElement)imageFallback(event.target);
  },true);
  document.querySelectorAll('img').forEach(image=>{
    if(image.getAttribute('src')&&image.complete&&image.naturalWidth===0)imageFallback(image);
  });
  const avatarInput=document.querySelector('[data-avatar-preview]');
  if(avatarInput){
    avatarInput.addEventListener('change',()=>{
      const file=avatarInput.files&&avatarInput.files[0];
      if(!file) return;
      const holder=document.querySelector('.account-avatar-preview');
      let preview=document.getElementById('avatarPreview');
      if(!preview){
        preview=document.createElement('img');
        preview.id='avatarPreview';
        preview.alt='Xem trước ảnh đại diện';
        holder?.appendChild(preview);
      }
      const placeholder=document.getElementById('avatarPlaceholder');
      if(placeholder) placeholder.remove();
      preview.src=URL.createObjectURL(file);
      const fileLabel=document.querySelector('.avatar-dropzone b');
      if(fileLabel) fileLabel.textContent=file.name;
    });
  }
  document.querySelectorAll('.password-toggle').forEach(button=>{
    const input=button.closest('.password-field')?.querySelector('input');
    if(!input) return;
    const icon=button.querySelector('i');
    const sync=()=>{
      const empty=input.value.length===0;
      if(empty) input.type='password';
      const visible=input.type==='text';
      button.hidden=empty;
      button.setAttribute('aria-label',visible?'Ẩn mật khẩu':'Hiện mật khẩu');
      button.setAttribute('aria-pressed',String(visible));
      if(input.id) button.setAttribute('aria-controls',input.id);
      if(icon){
        icon.className=visible?'bi bi-eye':'bi bi-eye-slash';
        icon.setAttribute('aria-hidden','true');
      }
    };
    button.addEventListener('mousedown',event=>event.preventDefault());
    button.addEventListener('click',()=>{
      if(!input.value) return;
      const start=input.selectionStart;
      const end=input.selectionEnd;
      input.type=input.type==='password'?'text':'password';
      sync();
      input.focus({preventScroll:true});
      if(start!==null&&end!==null) input.setSelectionRange(start,end);
    });
    input.addEventListener('input',sync);
    input.addEventListener('change',sync);
    input.addEventListener('focus',sync);
    input.form?.addEventListener('reset',()=>window.setTimeout(()=>{
      input.type='password';
      sync();
    },0));
    window.addEventListener('pageshow',sync);
    sync();
  });
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
  const cartItems=[...document.querySelectorAll('[data-cart-item]')];
  if(cartItems.length){
    const selectAll=document.querySelector('[data-cart-select-all]');
    const checkoutButton=document.querySelector('[data-cart-checkout]');
    const selectedCount=document.querySelector('[data-cart-selected-count]');
    const selectedTotal=document.querySelector('[data-cart-selected-total]');
    const money=new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND',maximumFractionDigits:0});
    const sync=()=>{
      const checked=cartItems.filter(item=>item.checked);
      const total=checked.reduce((sum,item)=>sum+Number(item.dataset.lineTotal||0),0);
      if(selectedCount) selectedCount.textContent=`Đã chọn ${checked.length}/${cartItems.length} dòng sản phẩm`;
      if(selectedTotal) selectedTotal.textContent=money.format(total);
      if(checkoutButton) checkoutButton.disabled=checked.length===0;
      if(selectAll){
        selectAll.checked=checked.length===cartItems.length;
        selectAll.indeterminate=checked.length>0&&checked.length<cartItems.length;
      }
      document.querySelectorAll('[data-cart-shop]').forEach(shop=>{
        const shopItems=cartItems.filter(item=>item.dataset.shopId===shop.dataset.cartShop);
        const shopChecked=shopItems.filter(item=>item.checked);
        const toggle=shop.querySelector('[data-cart-shop-toggle]');
        const label=shop.querySelector('[data-shop-selection]');
        if(toggle){
          toggle.checked=shopChecked.length===shopItems.length;
          toggle.indeterminate=shopChecked.length>0&&shopChecked.length<shopItems.length;
        }
        if(label) label.textContent=`${shopChecked.length}/${shopItems.length} dòng được chọn`;
      });
    };
    cartItems.forEach(item=>item.addEventListener('change',sync));
    selectAll?.addEventListener('change',()=>{cartItems.forEach(item=>item.checked=selectAll.checked);sync();});
    document.querySelectorAll('[data-cart-shop-toggle]').forEach(toggle=>toggle.addEventListener('change',()=>{
      const shop=toggle.closest('[data-cart-shop]');
      cartItems.filter(item=>item.dataset.shopId===shop?.dataset.cartShop)
        .forEach(item=>item.checked=toggle.checked);
      sync();
    }));
    sync();
  }
  const cartPreviewButton=document.querySelector('[data-nav-cart]');
  const cartPreviewMenu=document.getElementById('cartPreviewMenu');
  const cartPreviewList=cartPreviewMenu?.querySelector('[data-cart-preview-list]');
  const cartPreviewCount=cartPreviewMenu?.querySelector('[data-cart-preview-count]');
  const cartBadge=cartPreviewButton?.querySelector('.nav-counter');
  if(cartPreviewButton&&cartPreviewList){
    const money=new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND',maximumFractionDigits:0});
    let requestId=0;
    const showMessage=message=>{
      const empty=document.createElement('p');
      empty.className='cart-preview-empty';
      empty.textContent=message;
      cartPreviewList.replaceChildren(empty);
    };
    const loadCartPreview=async()=>{
      const currentRequest=++requestId;
      showMessage('Đang tải giỏ hàng…');
      try{
        const response=await fetch(cartPreviewButton.dataset.previewUrl,{credentials:'same-origin',cache:'no-store'});
        if(!response.ok)throw new Error('Không tải được giỏ hàng.');
        const cart=await response.json();
        if(currentRequest!==requestId)return;
        const items=Array.isArray(cart.items)?cart.items:[];
        const lineCount=items.length;
        if(cartBadge){
          cartBadge.textContent=String(lineCount);
          cartBadge.classList.toggle('d-none',lineCount===0);
        }
        if(cartPreviewCount)cartPreviewCount.textContent=`${new Intl.NumberFormat('vi-VN').format(lineCount)} món trong giỏ`;
        if(items.length===0){showMessage('Giỏ hàng đang trống. Hãy chọn món trà sữa bạn thích nhé!');return;}
        const fragment=document.createDocumentFragment();
        items.slice().sort((a,b)=>Number(b.id)-Number(a.id)).slice(0,5).forEach(item=>{
          const productId=String(item.productId??'');
          if(!/^[1-9]\d*$/.test(productId))return;
          const link=document.createElement('a');
          link.className='cart-preview-item';
          const itemId=String(item.id??'');
          link.href=/^[1-9]\d*$/.test(itemId)?`/cart#cart-item-${itemId}`:'/cart';
          const image=document.createElement('img');
          image.className='cart-preview-image';
          image.alt='';
          image.loading='lazy';
          const imageUrl=typeof item.imageUrl==='string'?item.imageUrl.trim():'';
          image.src=/^https?:\/\//i.test(imageUrl)||(imageUrl.startsWith('/')&&!imageUrl.startsWith('//'))
            ?imageUrl:'/images/product-placeholder.svg';
          const copy=document.createElement('span');
          copy.className='cart-preview-copy';
          const name=document.createElement('span');
          name.className='cart-preview-name';
          name.textContent=item.productName||'Sản phẩm';
          const meta=document.createElement('span');
          meta.className='cart-preview-meta';
          const quantity=Math.max(1,Number(item.quantity)||1);
          meta.textContent=[item.shop,item.size?`Size ${item.size}`:'',`×${quantity}`].filter(Boolean).join(' · ');
          const options=document.createElement('span');
          options.className='cart-preview-options';
          options.textContent=[item.sugarLevelLabel,item.iceLevelLabel,item.toppings].filter(Boolean).join(' · ');
          const price=document.createElement('strong');
          price.className='cart-preview-price';
          const amount=Number(item.lineTotal);
          price.textContent=Number.isFinite(amount)?`Thành tiền: ${money.format(amount)}`:'—';
          copy.append(name,meta);
          if(options.textContent)copy.append(options);
          copy.append(price);
          link.append(image,copy);
          fragment.append(link);
        });
        cartPreviewList.replaceChildren(fragment);
        if(!cartPreviewList.childElementCount)showMessage('Không có sản phẩm để hiển thị.');
      }catch{
        if(currentRequest===requestId){
          showMessage('Không tải được giỏ hàng. Vui lòng thử lại.');
          if(cartPreviewCount)cartPreviewCount.textContent='Chưa cập nhật số lượng';
        }
      }
    };
    cartPreviewButton.closest('.dropdown')?.addEventListener('show.bs.dropdown',loadCartPreview);
    if(!window.bootstrap)cartPreviewButton.addEventListener('click',loadCartPreview);
  }
  const toastEl=document.getElementById('live-notification');
  if(toastEl){
    const connectionStatus=document.getElementById('notification-connection-status');
    const countBadges=[...document.querySelectorAll('[data-notification-count]')];
    const previewButton=document.querySelector('[data-nav-notifications]');
    const previewMenu=document.getElementById('notificationPreviewMenu');
    const previewList=previewMenu?.querySelector('[data-notification-preview-list]');
    let previewRequest=0;
    const previewMessage=text=>{
      if(!previewList)return;
      const empty=document.createElement('p');
      empty.className='notification-preview-empty';
      empty.textContent=text;
      previewList.replaceChildren(empty);
    };
    const loadNotificationPreview=async()=>{
      if(!previewList||!previewButton)return;
      const request=++previewRequest;
      previewMessage('Đang tải thông báo…');
      try{
        const response=await fetch(previewButton.dataset.previewUrl,{credentials:'same-origin',cache:'no-store'});
        if(!response.ok)throw new Error('Không tải được thông báo. Vui lòng thử lại.');
        const items=await response.json();
        if(request!==previewRequest)return;
        if(!Array.isArray(items)||items.length===0){previewMessage('Bạn chưa có thông báo nào.');return;}
        const fragment=document.createDocumentFragment();
        items.forEach(item=>{
          const link=document.createElement('a');
          link.className='notification-preview-item'+(item.read?'':' is-unread');
          link.href=typeof item.link==='string'&&item.link.startsWith('/')&&!item.link.startsWith('//')&&!item.link.includes('\\')
            ? item.link:'/notifications';
          const title=document.createElement('span');
          title.className='notification-preview-title';
          if(!item.read){const dot=document.createElement('span');dot.className='notification-preview-dot';dot.setAttribute('aria-label','Chưa đọc');title.appendChild(dot);}
          const label=document.createElement('span');label.textContent=item.title||'Thông báo mới';title.appendChild(label);
          const message=document.createElement('span');message.className='notification-preview-message';message.textContent=item.message||'';
          const time=document.createElement('span');time.className='notification-preview-time';time.textContent=item.createdAt||'';
          link.append(title,message,time);fragment.appendChild(link);
        });
        previewList.replaceChildren(fragment);
      }catch{if(request===previewRequest)previewMessage('Không tải được thông báo. Vui lòng thử lại.');}
    };
    previewButton?.closest('.dropdown')?.addEventListener('show.bs.dropdown',loadNotificationPreview);
    if(!window.bootstrap)previewButton?.addEventListener('click',loadNotificationPreview);
    const seenKey='utetra-notification-toast-ids';
    let seenIds=[];
    try{seenIds=JSON.parse(sessionStorage.getItem(seenKey)||'[]');if(!Array.isArray(seenIds))seenIds=[];}catch{seenIds=[];}
    const seenSet=new Set(seenIds.map(String));
    let client=null,retryTimer=null,retries=0,connectionToken=0,connected=false,badgeVersion=0;
    const setStatus=text=>{
      if(!connectionStatus)return;
      connectionStatus.textContent=text;
      connectionStatus.classList.toggle('d-none',!text);
    };
    const setUnreadCount=value=>{
      badgeVersion++;
      const count=Math.max(0,Number(value)||0);
      countBadges.forEach(badge=>{
        badge.textContent=count>99?'99+':String(count);
        if(badge.classList.contains('position-absolute'))badge.classList.toggle('d-none',count===0);
      });
    };
    const refreshUnreadCount=async()=>{
      const version=badgeVersion;
      try{
        const response=await fetch('/api/notifications/unread-count',{credentials:'same-origin',cache:'no-store'});
        if(response.ok){
          const data=await response.json();
          if(version===badgeVersion)setUnreadCount(data.count);
        }
      }catch{/* Giữ số đếm hiện tại khi mạng chưa ổn định. */}
    };
    const scheduleReconnect=()=>{
      connected=false;
      setStatus(navigator.onLine?'Mất kết nối. Đang kết nối lại…':'Mất mạng. Sẽ kết nối lại khi có mạng.');
      if(retryTimer||!navigator.onLine)return;
      const delay=Math.min(30000,1000*2**Math.min(retries++,5));
      retryTimer=setTimeout(()=>{retryTimer=null;connect();},delay);
    };
    const connect=()=>{
      if(!navigator.onLine){scheduleReconnect();return;}
      if(!window.SockJS||!window.Stomp){scheduleReconnect();return;}
      const token=++connectionToken;
      if(client?.ws&&client.ws.readyState<2)client.ws.close();
      setStatus(retries?'Đang kết nối lại…':'Đang kết nối thông báo…');
      try{
        const socket=new SockJS('/ws');
        client=Stomp.over(socket);client.debug=()=>{};
        client.heartbeat.outgoing=10000;
        client.heartbeat.incoming=10000;
        client.connect({},()=>{
          if(token!==connectionToken)return;
          connected=true;retries=0;setStatus('');
          refreshUnreadCount();
          client.subscribe('/user/queue/notifications',message=>{
            let data;
            try{data=JSON.parse(message.body);}catch{return;}
            if(data.unreadCount!==undefined)setUnreadCount(data.unreadCount);
            if(previewMenu?.classList.contains('show'))loadNotificationPreview();
            const id=data.id==null?null:String(data.id);
            if(id&&seenSet.has(id))return;
            if(id){
              seenSet.add(id);seenIds.push(id);
              if(seenIds.length>100)seenSet.delete(seenIds.shift());
              try{sessionStorage.setItem(seenKey,JSON.stringify(seenIds));}catch{/* Không chặn toast nếu storage bị khóa. */}
            }
            toastEl.querySelector('.toast-header strong').textContent=data.title||'UTETra';
            toastEl.querySelector('.toast-body').textContent=data.message||'Bạn có thông báo mới.';
            bootstrap.Toast.getOrCreateInstance(toastEl).show();
          });
          client.subscribe('/user/queue/order-status',message=>{
          const data=JSON.parse(message.body);
          const timeline=document.getElementById('orderStatusTimeline');
          if(!timeline||String(data.orderId)!==timeline.dataset.orderId)return;
          const item=document.createElement('li');
          item.className='order-timeline-item'+(['CANCELLED','DELIVERY_FAILED','RETURNED_REFUNDED'].includes(data.status)?' timeline-danger':'');
          const marker=document.createElement('span');marker.className='timeline-marker';marker.innerHTML='<i class="bi bi-check-lg"></i>';
          const content=document.createElement('div');content.className='timeline-content';
          const top=document.createElement('div');top.className='d-flex flex-wrap justify-content-between gap-2';
          const label=document.createElement('strong');label.textContent=data.label;
          const time=document.createElement('time');
          const changedAt=/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(data.changedAt||'');
          time.textContent=changedAt?`${changedAt[4]}:${changedAt[5]} · ${changedAt[3]}/${changedAt[2]}/${changedAt[1]}`:'';
          const meta=document.createElement('div');meta.className='timeline-meta';
          const actor=document.createElement('span');actor.textContent='Cập nhật bởi '+data.changedBy;meta.appendChild(actor);
          if(data.note){const note=document.createElement('span');note.textContent=data.note;meta.appendChild(note);}
          top.append(label,time);content.append(top,meta);item.append(marker,content);timeline.appendChild(item);
        });
        },()=>{if(token===connectionToken)scheduleReconnect();});
        socket.addEventListener?.('close',()=>{if(token===connectionToken)scheduleReconnect();});
      }catch(error){scheduleReconnect();}
    };
    window.addEventListener('offline',()=>{
      connected=false;
      if(retryTimer){clearTimeout(retryTimer);retryTimer=null;}
      if(client?.ws&&client.ws.readyState<2)client.ws.close();
      setStatus('Mất mạng. Sẽ kết nối lại khi có mạng.');
    });
    window.addEventListener('online',()=>{
      if(connected)return;
      if(retryTimer){clearTimeout(retryTimer);retryTimer=null;}
      connect();
    });
    document.addEventListener('visibilitychange',()=>{if(!document.hidden)refreshUnreadCount();});
    setInterval(refreshUnreadCount,60000);
    connect();
  }
});
