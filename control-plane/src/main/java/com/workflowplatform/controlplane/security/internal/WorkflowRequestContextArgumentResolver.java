package com.workflowplatform.controlplane.security.internal;

import com.workflowplatform.contracts.problem.ProblemCode;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Resolves only the trusted request attribute populated by the authentication layer. */
public final class WorkflowRequestContextArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return WorkflowRequestContext.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer container,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        Object attribute =
                request == null
                        ? null
                        : request.getAttribute(WorkflowRequestContext.REQUEST_ATTRIBUTE);
        if (attribute instanceof WorkflowRequestContext context) {
            return context;
        }
        throw ApiProblemException.of(ProblemCode.AUTH_AUTHENTICATION_REQUIRED);
    }
}
