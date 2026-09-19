document.querySelector('#login-form').addEventListener('submit',async event=>{
  event.preventDefault();const button=event.target.querySelector('button');button.disabled=true;
  const error=document.querySelector('#login-error');error.textContent='';
  try {
    const csrfResponse=await fetch('/api/csrf');if(!csrfResponse.ok) throw new Error('Cannot connect to MindBridge.');
    const csrf=await csrfResponse.json();
    const body=new URLSearchParams(new FormData(event.target));
    const response=await fetch('/login',{method:'POST',headers:{[csrf.headerName]:csrf.token},body});
    if(!response.ok) throw new Error(response.status===401?'Username or password is incorrect.':'Sign-in failed. Please refresh and try again.');
    location.assign('/');
  } catch(e){error.textContent=e.message;} finally {button.disabled=false;}
});